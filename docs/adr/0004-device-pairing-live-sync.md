# 0004 — In-app login + live plan sync via device-pairing bearer tokens

- **Status:** Accepted (retroactive — PR #2)
- **Date:** 2026-06-27
- **Deciders:** Viktor + Claude
- **Builds on:** [0002](0002-multi-user-login-with-strava.md), [0003](0003-apple-workouts-export-workoutkit.md)

## Context

[0003](0003-apple-workouts-export-workoutkit.md) shipped file import (download `.itw`
→ open in app). The next step removes the file step: the iOS app **logs in once and
pulls the plan over HTTP**, re-syncing after re-plans. But the API was **cookie-session
only** ([0002](0002-multi-user-login-with-strava.md)), which a native app can't use
cleanly.

## Decision

Add a **device-pairing → bearer-token** path that **coexists** with cookie sessions.

- **Pairing = QR.** The logged-in web UI mints a short-lived, single-use pairing
  code and shows it as a QR (`irontrainer://pair?server=…&code=…`). The iOS app
  scans it (camera) or accepts manual entry, then exchanges the code for a
  long-lived **bearer token** stored in the **Keychain**.
- **Bearer auth reuses the contextvar.** `AuthContextMiddleware` checks
  `Authorization: Bearer <token>` **before** the session and resolves the same
  `current_athlete_id()` — so all existing per-athlete scoping just works. Tokens are
  stored **sha256-hashed**; codes are single-use and expire (~10 min).
- **Works in both auth modes** (pairs to the default athlete when `AUTH_REQUIRED` off).
- **`GET /api/export/plan.itw`** returns the whole plan as one doc; the app shows a
  list → tap one for the date-picker preview, **and** a "Schedule next 7 days" batch
  (respecting WorkoutKit's 15-workout cap).

## Alternatives considered

- **JWT** — rejected: opaque random token + server-side hashed lookup is simpler, and
  trivially revocable (delete the row); no key management.
- **App-shows-code → approve-on-web (polling)** — rejected in favor of web-shows-QR →
  scan: fewer moving parts, no polling, one scan configures server URL + token.
- **Reuse the session cookie in the app** — rejected: fragile cookie handling in a
  native client; bearer is the standard, durable native pattern.

## Consequences

- (+) One-scan setup; the app re-fetches the live plan; isolation is identical to web
  (same contextvar).
- (+) Additive — web sessions untouched; bearer simply wins when present.
- (−) Tokens are long-lived; revocation is delete-the-row (no rotation UI yet — future).
- (−) The QR encodes `window.location.origin`; from a localhost dev server a phone
  can't reach it — use the deployed URL (or LAN IP) for on-device pairing.

## Related decisions captured in this PR

- **Logging:** introduced a structured `iron` log namespace (`LOG_LEVEL`) across
  startup/Strava/planning/device-auth. **Fix:** alembic `env.py` called `fileConfig()`
  during in-process `init_db()` with the default `disable_existing_loggers=True`,
  which silenced the app + uvicorn loggers after the first migration (affected
  [0001](0001-database-abstraction-sqlmodel-alembic.md)'s startup too). Now
  `disable_existing_loggers=False`.
- **Liquid Glass (iOS 26):** standard controls adopt glass automatically (built with
  the iOS 26 SDK; no suppression). Primary "Schedule" buttons use `.glassProminent`
  (iOS 26+) with a `.borderedProminent` fallback for the iOS 18 deployment target;
  Regular variant throughout (no media-rich content); content layer stays non-glass.

## Implementation notes

Backend: `DeviceToken` table + migration `a1c2d3e4f5a6`; `repo`
(`create_pairing_code`/`claim_pairing_code`/`athlete_id_for_bearer`);
`app/auth.py` (bearer in `AuthContextMiddleware`); `routers/auth_router.py`
(`/api/device/pairing-code`, `/api/device/claim`); `export_router` (`plan.itw`).
Web: `Setup.tsx` "Connect iOS app" QR (`qrcode.react`). iOS: `AuthModel` + `Keychain`,
`PlanNetworkSource`, `SettingsView` (VisionKit QR scan), `WorkoutListView`,
`irontrainer://` deep link.

## Verification

`tests/test_device_auth.py` (pairing→claim→bearer, isolation, expiry/single-use,
plan.itw over bearer; SQLite + Postgres). End-to-end via curl; iOS verified on
simulator (pairing signs in) and shipped to TestFlight. Live on Railway+Supabase
(`/api/health?deep=1` → `database: ok`, `device_token` migration applied). Shipped in PR #2.
