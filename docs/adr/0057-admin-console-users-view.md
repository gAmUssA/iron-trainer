# 0057 — Admin console: Users view (2026-08-08)

- **Status:** Accepted
- **Beans:** y8b2 (slice 2 of admin epic 18n4)

## Context

Slice 1 (ADR 0056) gave the password-gated admin console a Jobs view. Debugging a
user's sync problems still meant cross-referencing job rows by athlete id with no
way to see *who* the athletes are, which accounts they've connected, or their data
footprint. This slice adds the Users view.

## Decision

- **`GET /api/admin/users`** (`@RequireAdmin`) — every athlete: id, name,
  `strava_athlete_id`, derived `connected` (has `strava_refresh_token`) and
  `apple_linked` (has `apple_user_id`) flags, plus activity / job / failed-job
  counts.
- **`GET /api/admin/users/{id}`** (`@RequireAdmin`) — the same summary plus
  thresholds (ftp/hr/target), data counts (activities, recovery days, jobs),
  **last job per kind** (a `max(id)` group-by, so a rarely-run kind whose last run
  is far older than the recent window still shows as "ran long ago", not "never
  ran") and the 10 most recent jobs (a separate newest-first query).
- **Never expose secrets.** A shared `summary()` helper builds the athlete view and
  deliberately excludes `strava_access_token` / `strava_refresh_token`; only the
  boolean `connected` flag leaks. A regression test asserts the raw response
  contains neither token string.
- **Frontend:** the admin app grew a Users/Jobs nav shell; the old single-view
  console became `JobsView`, and `UsersView` (table + JSON detail drawer) sits
  alongside it. Connection state renders as pills (connected / linked / failed-count).

## Alternatives considered

- **Aggregate counts via one group-by query** — rejected for now: the roster is a
  handful of athletes, so per-user count queries (N+1) are trivially cheap. Noted
  as the upgrade path if the user base ever grows.
- **A joined date / "member since"** — the `athlete` table has no `created_at`
  column, so it's omitted rather than faked (bean note; add a column later if
  wanted).
- **Rich typed detail model on the frontend** — the detail drawer renders the JSON
  structurally, so it stays loosely typed (`Record`) like the job detail; no schema
  duplication.

## Consequences

- Admins can see connected accounts + data footprint per user and jump from a user
  to their recent jobs — the missing half of "why is this user's sync failing?".
- The detail endpoint returns thresholds (non-secret profile data) — acceptable for
  an internal admin; no tokens or PII beyond name + Strava id.
- Next slice: sync-health telemetry (j41l) — failure rates per kind, recent-failures
  feed.

## Verification

Backend `AdminUsersResourceTest` — guard 401 without cookie; login→cookie→users 200
with an assertion that the response leaks neither Strava token. Frontend `tsc` +
vite build clean. Local multi-agent review (security / correctness / simplicity) +
adversarial verify pass before merge.
