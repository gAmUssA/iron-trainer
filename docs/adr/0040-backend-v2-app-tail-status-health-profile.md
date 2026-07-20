# 0040 — Backend v2: app tail — health, status, me, logout, profile read (2026-07-20)

Date: 2026-07-20
Epic: iron-trainer-eom4 (Phase 7) · Pattern: ADR 0020

## Context

An audit of the Phase-7 cutover gate found 14 endpoints still FastAPI-only. This
slice ports the low-risk, byte-parity-friendly subset — the app-level reads +
trivial writes — clearing a chunk of that backlog. Deferred to their own slices
(shared heavier dependencies): the profile **PUT** (recompute-TSS + future-plan
refresh), the health **ingest/recovery** pair (full daily_recovery entity + the
Health-Auto-Export parser), the **export ZIP** bundles (zip byte-parity), and
**device pairing** (bearer-token minting — a security slice like OAuth).

## What was built

- **`GET /api/health`** (`HealthResource`) — liveness `{status, version}`; `?deep=1`
  also runs `SELECT 1` and returns `database: ok`, or 503 `{status: degraded,
  database: error, detail}` when the DB is down. Unauthenticated probe.
- **`GET /api/status`** (`StatusResource`) — onboarding status: `{version, race,
  strava_configured, anthropic_configured, auth_required, authenticated}`. `race`
  is `effectiveRace` (athlete's when authenticated, config default otherwise);
  `anthropic_configured` treats the `no-key` boot sentinel + blank as unconfigured.
- **`GET /api/me`** — `{authenticated, auth_required, athlete{name, strava_athlete_id}|null}`.
- **`POST /api/auth/logout`** — `{ok: true}`; emits the delete `Set-Cookie`
  (`session=null; …; expires=Thu, 01 Jan 1970 …`) **only when a session cookie was
  present**, matching Starlette (a bearer-only client with no session gets none).
- **`GET /api/athlete`** (`ProfileResource`) — `{connected, profile{…_PUBLIC…}}`.
  Added the `gi_tolerance` column to the `Athlete` entity (schema had it; the
  entity didn't map it) so the profile read is complete.

`version` is pinned to `0.1.0` to match FastAPI `app.__version__`.

## Testing

- **`StatusEndpointsTest`** (`@QuarkusTest`): health (+deep), status/me/athlete
  shapes, and logout with/without a session cookie (Set-Cookie presence).
- **Parity** (`test_health_parity`, `test_athlete_profile_parity`,
  `test_status_parity`, `test_me_parity`, `test_logout_parity`): byte-identical vs
  real FastAPI + backend-v2. `status`/`me` normalize out the single `auth_required`
  field — the parity harness deliberately runs FastAPI with `auth_required=false`
  and backend-v2 with `true`, so that passthrough legitimately differs; everything
  else is byte-equal.
- Full v2 suite: 173 green.

## Remaining for Phase 7

- PUT /api/athlete/profile (recompute TSS + refresh future plan targets)
- GET /api/health/recovery + POST /api/health/ingest (daily_recovery entity + parser)
- GET /api/export/plan.zip + /api/export/week/{week_start}.zip (zip bundles)
- Device pairing: /api/device/pairing-code, /device/claim, /device/ingest-token,
  DELETE /api/device/tokens (bearer-token minting — security slice)
