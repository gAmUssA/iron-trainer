# 0056 — Admin console: password-gated ops + jobs view (2026-08-08)

- **Status:** Accepted
- **Beans:** gfb3 (slice 1 of admin epic 18n4)

## Context

Debugging the sync failures (Strava, Apple Health, dedup, check-in) needed a way to
see users and inspect background jobs across *all* athletes. The `Job` table already
records kind/status/timings/error/result; `JobResource` only exposed it per-athlete.
No admin concept existed. Per Viktor, admin access must be **password-protected and
decoupled from any user account** (not an athlete-id allowlist).

## Decision

- **Auth = a shared password, not a user.** `ADMIN_PASSWORD` env → `POST
  /api/admin/login` (constant-time compare) mints a signed `admin_session` cookie
  (reuses the itsdangerous HMAC signer; payload carries a 12h `exp` enforced
  server-side, not just the browser Max-Age). `AdminAuthFilter` (`@RequireAdmin`
  name-binding) 401s the data endpoints; the admin endpoints never touch
  `CurrentAthlete`. Unset password → 503 (console disabled), boot-safe.
- **Jobs view:** `GET /api/admin/jobs` (cross-athlete, filter kind/status/athlete_id,
  paginated, truncated error) + `/jobs/{id}` (full result/error). Frontend: a
  separate `/admin` route (React) — password login → jobs table → result/error drawer.
- **Brute-force friction:** reuse `ClaimThrottle` (per-client, keyed `admin:<client>`).

## Alternatives considered

- **HTTP Basic Auth** — simpler but clunky in the SPA (native prompt, no clean
  logout); the signed-cookie login is nicer and reuses existing infra.
- **Admin = an athlete-id allowlist** — rejected: Viktor wanted it decoupled from
  user accounts.

## Consequences

- Job detail returns full result/error — the same data already user-facing via
  `/api/jobs/{id}`, so no new exposure (accepted in review).
- A captured cookie is bounded to 12h (server-side `exp`); true revocation would need
  stateful sessions — out of scope for an internal tool. Rotate `ADMIN_PASSWORD` to
  invalidate everything.
- Next slices: Users view (y8b2), sync-health telemetry (j41l).

## Verification

Backend `AdminSessionTest` 5/5 (sign/verify, tamper, expiry) + `AdminResourceTest`
(login 401/503, guard 401, login→cookie→jobs 200); frontend builds. Three review
passes (local multi-agent, Copilot, self) — fixes: real 12h expiry, login throttle,
strict athlete_id, status messages, pill contrast. Deploy verified: guard 401, login
200 in prod.
