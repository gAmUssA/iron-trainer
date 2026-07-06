# ADR 0010 — Security & robustness hardening from the full-stack review

**Status:** Accepted · 2026-07-06

## Context

A full-stack code review (frontend, backend, iOS, and the new nutrition feature —
findings tracked in [docs/reviews/2026-07-06-code-review-findings.md](../reviews/2026-07-06-code-review-findings.md))
surfaced one conditional-HIGH and six MEDIUM issues. This change fixes every HIGH
and MEDIUM plus the one-line LOWs in files already being touched. LOW/INFO items
remain open in the tracker.

## Decisions

### B1 — Fail startup when multi-user auth runs on the default session secret
Session cookies are itsdangerous-signed payloads carrying `athlete_id`; with the
known default `SESSION_SECRET` anyone could forge a session for any athlete,
nullifying the allowlist. `enforce_secure_config()` in `main.py` now raises at
startup when `auth_required` is true and the secret is the default — a failed
deploy beats a silently forgeable auth system. *Alternative considered:* log a
warning and serve — rejected; nobody reads warnings until it's too late.

### B2 — Decompression/size limits in the archive importer
`strava_import.py` caps every ZIP member (and every gzip payload) at
`MAX_MEMBER_BYTES` (100 MB decompressed); oversized members fall back to the CSV
summary exactly like unparseable files, preserving the "robust by design"
contract. The upload endpoint streams to disk counting bytes and rejects >2 GB
with HTTP 413. Also builds the archive name-set once (was O(n²) per CSV row).

### B3 — Device bearer tokens are now revocable
`DELETE /api/device/tokens` revokes all paired devices for the athlete;
`disconnect_strava()` purges DeviceTokens alongside activities/metrics (summary
gains `revoked_devices`); iOS `signOut()` calls the endpoint best-effort before
clearing local state. *Alternative considered:* per-device revocation + token
expiry — deferred; all-devices revocation covers the realistic single-user threat
(lost phone) with a fraction of the surface.

### I1 — Pairing deep link hardened
`parsePairingPayload` now requires `https` for the server URL (plain `http` only
for localhost/`.local`/RFC-1918 dev hosts — ATS blocks it anyway in release), and
when the app is already signed in to a *different* server, `onOpenURL` shows a
confirmation alert instead of silently replacing the session. A malicious
`irontrainer://pair?server=…` link can no longer silently re-point the app.

### N1 — Fueling safety validator aggregates per phase
`validate_fueling()` previously rate-checked each timeline item in isolation, so
LLM output could evade the caps (several same-phase items each under the cap; or
amounts in duration-less phases like T1 that were never checked at all). It now
sums carbs/fluid per phase against the phase duration and scales members
proportionally, and applies absolute ceilings to duration-less items
(transitions: 80 g / 500 mL; meals: 300 g / 1000 mL). The "always
safety-validated" claim in the router/UI is now true.

### N3 — Bounds on athlete profile input
`ProfileUpdate` fields carry generous sanity bounds (e.g. body weight 25–250 kg,
FTP < 1000 W) and `gi_tolerance` is a `Literal`; a typo can no longer produce
negative carb targets. N2's division-by-zero (projected bike leg < 45 min) is
guarded.

### F1–F3 — Frontend correctness
- Pace formatters round total seconds before splitting (no more `4:60/km`,
  which also round-tripped corrupted values into stored thresholds).
- `App.tsx` routes all post-action refreshes through `safeLoad`/`reload`
  wrappers that surface failures in the error banner (previously unhandled
  rejections → silent stale data); `ProfileEditor.save` catches and shows errors.
- Threshold inputs are validated before save: non-numeric text aborts with a
  visible message instead of silently JSON-nulling the field.

## Consequences

- Deploys with `AUTH_REQUIRED=true` **must** set `SESSION_SECRET` (Railway
  already does; local no-auth mode is unaffected).
- iOS sign-out now invalidates the token server-side — re-pairing is required
  after sign-out (intended).
- `disconnect` response schema gains `revoked_devices`.
- LLM race-day timelines may show scaled-down amounts with a "capped" adjustment
  note where they previously passed through.

## Verification

- `uv run pytest` — 127 passed (11 new tests in `tests/test_hardening.py`
  covering B1/B2/B3/N1/N3); `uv run ruff check app tests` clean.
- `npm run build` clean; `xcodegen + xcodebuild` (simulator, no signing) clean.
- Existing validator tests unchanged and green (single-item phases behave
  identically under per-phase aggregation).

## Open items (tracker)

B4 claim throttle, B5 null-clearing semantics, B6 health error detail, F5/F6,
I2/I3, N4 (§5.3 AI-decoupling decision) — see the findings tracker.
