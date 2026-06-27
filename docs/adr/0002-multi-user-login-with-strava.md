# 0002 — Multi-user: Login with Strava + per-athlete data isolation

- **Status:** Accepted (retroactive)
- **Date:** 2026-06 (greenfield build)
- **Deciders:** Viktor + Claude
- **Builds on:** [0001](0001-database-abstraction-sqlmodel-alembic.md)

## Context

The app began single-tenant (one fixed athlete, no auth). To deploy it for more
than one person we needed authentication and strict per-user data isolation —
without passwords to manage, and **without breaking the zero-login local
experience**. The user already authenticates to Strava (the data source), so
Strava is the natural identity.

## Decision

**Identity = the `athlete` row, keyed by a unique `strava_athlete_id`.** OAuth with
Strava doubles as login. Two modes via `AUTH_REQUIRED`:
- **off (default, local):** no login; everything runs as `default_athlete_id` (1).
- **on (deployed):** Strava login required; an **allowlist** (`ALLOWED_STRAVA_IDS`)
  gates who may register; sessions are signed cookies (Starlette `SessionMiddleware`).

**Current user is carried in a `ContextVar`** set by a **pure-ASGI** middleware
(`AuthContextMiddleware`) from the session. `repo` reads `current_athlete_id()` and
scopes every query by `athlete_id`, so repo signatures and all callers stay
user-agnostic — the smallest possible diff to go multi-tenant. Data is partitioned
by an `athlete_id` FK on `activities`/`plan`/`planned_workouts` and a composite PK
`(athlete_id, date)` on `metrics_daily`. The race catalog stays global.

## Alternatives considered

- **Passwords / email auth** — rejected: more to secure and manage; Strava login is
  already required for the core data.
- **`BaseHTTPMiddleware` for the contextvar** — rejected: it runs the endpoint in a
  different task, so the contextvar wouldn't propagate. Pure-ASGI middleware works
  because anyio copies the context into the sync-endpoint threadpool. (This is the
  single most important implementation constraint here.)
- **Threading `athlete_id` through every repo call signature** — rejected: huge
  diff, easy to miss a call and leak data across users. The contextvar centralizes it.

## Consequences

- (+) Local dev unchanged (no login); same code serves a multi-user deployment by
  flipping env vars.
- (+) Isolation is enforced in one place (`current_athlete_id()` + per-query
  filter), and proven by tests that set the contextvar to athlete A vs B.
- (−) Anything reading user data MUST go through `repo` (which applies the scope);
  bypassing it would bypass isolation.
- (−) Strava's API rate limit is **per app** (shared across users) — a reason to
  keep the allowlist tight; documented in `docs/deploy.md`.
- Enabled later work: device-pairing bearer auth ([0004](0004-device-pairing-live-sync.md))
  resolves the same contextvar from a token instead of a session.

## Implementation notes

`app/auth.py` (contextvar + `AuthContextMiddleware` + `current_athlete_id`/
`is_allowed`), `app/main.py` (middleware order: CORS → Session → Auth),
`app/routers/strava_router.py` (OAuth connect/callback, CSRF `state`),
`app/routers/auth_router.py` (`/api/me`, `/api/auth/logout`), `app/repo.py`
(per-athlete scoping). Frontend: login gate + logout when `auth_required`.

## Verification

`tests/test_auth.py` — per-user isolation (activities/plans/workouts), allowlist
allow/deny, login flow through real cookies, local-mode default, 401 when required.
Runs green on SQLite + Postgres.
