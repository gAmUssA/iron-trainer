# Code Review Findings — 2026-07-06

Full-stack security/correctness review at `5d56c69` (frontend, backend, iOS, nutrition
feature). Check items off as they are fixed; each entry cites the file so regressions
are easy to re-check. Fixed-in column filled when the fix merges.

Severity: 🔴 HIGH · 🟠 MEDIUM · 🟡 LOW · ⚪ INFO

## Backend

- [x] 🔴 **B1 — No guard against multi-user auth with the default session secret.**
  `config.py:45` defaults `session_secret="dev-insecure-change-me"`; session cookies
  carry `{"athlete_id": N}` signed with it. If `AUTH_REQUIRED=true` deploys without
  `SESSION_SECRET`, anyone can forge a session for any athlete (full auth bypass,
  allowlist nullified). *Fix: refuse to start when `auth_required` and the secret is
  the default.* → fixed in `fix/review-hardening`
- [x] 🟠 **B2 — Archive importer has no decompression/size limits.**
  `strava_import.py:188-206` reads ZIP members and `gzip.decompress`es unbounded;
  `strava_router.py:112` streams uploads to disk with no cap. Zip/gzip bomb → OOM or
  disk-fill (authenticated users only). *Fix: per-member decompressed cap, bounded
  gzip read, upload byte cap.* → fixed in `fix/review-hardening`
- [x] 🟠 **B3 — Device bearer tokens are immortal and irrevocable.**
  `repo.py:141-178` mints tokens with no expiry; no revocation endpoint; iOS
  `signOut()` and `disconnect_strava()` leave `DeviceToken` rows valid forever.
  *Fix: `DELETE /api/device/tokens`, called from iOS sign-out; purge tokens in
  `disconnect_strava()`.* → fixed in `fix/review-hardening`
- [x] 🟡 **B4 — Unauthenticated `/api/device/claim` has no throttle** on 32-bit
  pairing codes (`repo.py:133`, `auth_router.py:53`). Infeasible to brute-force at
  this scale, but zero friction. *Fix: simple attempt throttle or `token_hex(6)`.* → fixed in `fix/low-info-findings` (10 failures/min → 429)
- [x] 🟡 **B5 — Thresholds can never be cleared.** `save_profile` (`repo.py:189`)
  skips `None`, so the UI's "Saved" is a silent no-op for emptied fields. Now also
  covers the 4 nutrition fields. *Fix: distinguish absent vs explicit null.* → fixed in `fix/low-info-findings` (`exclude_unset`; inference/seeding filter Nones)
- [x] 🟡 **B6 — `/api/health?deep=1` echoes raw DB errors** (`main.py:100`) — DSN
  shape (host/user/db) leaks on an unauthenticated endpoint. *Fix: generic detail,
  log the real error.* → fixed in `fix/low-info-findings`
- [x] ⚪ **B7a — O(n²) `zf.namelist()` scan per CSV row** (`strava_import.py:181-184`).
  *Fix: build the name set once.* → fixed in `fix/review-hardening`
- [x] ⚪ **B7b — OAuth state not validated in local no-auth mode** (`strava_router.py:54`) —
  localhost-only login CSRF; **accepted as-is** (single-user local mode).
- [x] ⚪ **B7c — NP assumes 1 Hz and splices out recording gaps** (`metrics.py:27`);
  FIT session NP wins when present, **accepted as-is** (documented approximation).

## iOS

- [x] 🟠 **I1 — Pairing deep link: `server` unvalidated, sign-in silent.**
  `QRScannerView.swift:50-57` accepts any URL; `IronTrainerApp.swift:15-26` silently
  replaces the stored server+token on any `irontrainer://pair` link — one malicious
  link/QR re-points the app to an attacker server. *Fix: require https (http for
  local/dev hosts only) + confirmation alert before switching servers when already
  signed in.* → fixed in `fix/review-hardening`
- [x] 🟡 **I2 — Deep-link pairing failures swallowed** (`IronTrainerApp.swift:18`
  `try? await auth.signIn`). No feedback on expired code / offline server. → fixed in `fix/low-info-findings` (alert on deep-link pairing failure)
- [x] 🟡 **I3 — Scheduling "today" after 23:00 silently lands in the past.**
  `WorkoutScheduling.swift:51-54` — hour/minute roll past midnight but day stays
  today; WorkoutScheduler ignores past dates silently. → fixed in `fix/low-info-findings` (clamps to 23:45 today)
- [x] ⚪ **I4 — Clean:** Keychain storage, `.itw` schema gating, pace→speed math all
  verified correct. No action.

## Frontend

- [x] 🟠 **F1 — Pace formatters render `4:60`.** `Math.round(sec % 60)` → 60 in
  `api.ts:304-315` (`paceKm`, `pace100`) and `Setup.tsx:240-245` (`fmtPace`); Setup
  round-trips the corruption into stored thresholds. *Fix: round total seconds
  first.* → fixed in `fix/review-hardening`
- [x] 🟠 **F2 — Refresh/save rejections silently dropped.** `App.tsx:88-90` `reload`
  and all `onSynced`/`onChanged`/`onSaved` paths lack `.catch`; `ProfileEditor.save`
  has `try/finally` with no catch — failed saves show nothing. *Fix: safe wrappers
  at the source + error note in ProfileEditor.* → fixed in `fix/review-hardening`
- [x] 🟠 **F3 — Non-numeric threshold input saves as garbage.** `parsePace`/`parseFloat`
  (`Setup.tsx:232-267`) produce `NaN` → JSON `null`; combined with B5 the save
  silently no-ops while showing "Saved". *Fix: validate at the boundary, abort save
  with a visible error.* → fixed in `fix/review-hardening`
- [x] 🟡 **F4 — CSRF posture of multipart import** — VERIFIED SAFE: session cookie is
  `SameSite=lax` (`main.py:65`). No change needed.
- [x] 🟡 **F5 — HTTP errors discard backend detail** (`api.ts:189-204`) — import
  failures show "400 Bad Request" instead of the reason. → fixed in `fix/low-info-findings` (backend `detail` surfaced)
- [x] 🟡 **F6 — Pairing-code one-frame "expired" flash + client-clock skew**
  (`Setup.tsx:8-35`). → fixed in `fix/low-info-findings` (server `expires_in` countdown, seeded pre-render)
- [x] ⚪ **F7 — Nits:** `paceKm(0)` falsy check; race countdown doesn't tick across
  midnight; `doImport` file-read order verified correct. → `paceKm`/`pace100` now explicit `== null || <= 0`.

## Nutrition (PR #9)

- [x] 🟠 **N1 — `validate_fueling` bypassable by well-formed LLM output.**
  `nutrition.py:363-403`: only items with `phase_duration_s` are rate-checked
  (transitions/pre-race amounts pass unchecked), and multiple items in one phase
  each inherit the FULL phase duration (aggregate never summed) — despite the
  "always safety-validated" promise. *Fix: aggregate per phase; absolute clamps for
  duration-less phases.* → fixed in `fix/review-hardening`
- [x] 🟡 **N2 — ZeroDivisionError when projected bike leg < 45 min**
  (`nutrition.py:300`, `bike_carb_h == 0`). → fixed in `fix/review-hardening`
- [x] 🟡 **N3 — No bounds on new profile numeric fields** (`athlete_router.py`
  `ProfileUpdate`): negative body weight → "−560 g carbs/day"; `gi_tolerance`
  free-form server-side. *Fix: pydantic bounds + Literal.* → fixed in
  `fix/review-hardening`
- [ ] ⚪ **N4 — Compliance (§5.3):** `generate_race_day_nutrition` sends
  Strava-derived readiness projections to the LLM — add to the AI-decoupling list
  (see `docs/research/strava-ingestion-and-ai.md`). Decision, not a code fix.
