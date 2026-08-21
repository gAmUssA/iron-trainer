---
# iron-trainer-zvc2
title: 'Fresh self-host install cannot write: athlete 1 never exists'
status: completed
type: bug
priority: high
created_at: 2026-08-19T14:24:11Z
updated_at: 2026-08-21T02:18:26Z
parent: iron-trainer-4lve
---

A fresh self-host install cannot write anything. Reads look fine, so the failure is
invisible until the athlete tries to do something.

## Reproduce

```
docker compose up      # clean volume, no Strava, no account
curl -X POST localhost:8080/api/tests/result \
  -H 'Content-Type: application/json' \
  -d '{"test_slug":"bike-ftp-20","date":"2026-01-05","inputs":{"avg_power_w":240}}'
```

```
HTTP 500
insert or update on table "fitness_test_result" violates foreign key constraint
"fk_fitness_test_result_athlete_id_athlete"
Detail: Key (athlete_id)=(1) is not present in table "athlete".
```

## Cause

`AUTH_REQUIRED` defaults false and `DEFAULT_ATHLETE_ID` defaults 1, so every request
resolves to athlete 1 — but **nothing ever creates athlete 1**. On the SaaS deployment
the row is created by Strava OAuth / Apple sign-in / device pairing, so this never
surfaces. A self-hoster who has not connected Strava has no athlete row at all.

`/api/status` cheerfully reports `authenticated:true` and read endpoints return 200
with empty payloads, which is why the earlier "zero configuration, just works"
verification missed it: it only exercised reads. Every write path is broken.

## Found by
The upgrade test (bean yijb) on its very first run, trying to seed data through the
normal API against a fresh install. It never reached the migration step.

## Fix direction
Create the local athlete on first boot when local mode is on — which is the natural
home for it in the local-login epic (4lve), alongside naming the athlete and setting
race + date. Wherever it lands it must be idempotent (boot, restart, upgrade) and must
NOT fire on the SaaS deployment, where athlete rows are owned by the auth flow.

## Todo
- [x] Create the default athlete row on first boot in local mode, idempotently
- [x] Assert it does not run under the %prod SaaS profile
- [x] Regression test: fresh DB -> POST a write -> 200, not 500

## Fixed 2026-08-20 — LocalAthleteBootstrap

Startup observer that inserts the default athlete when `auth-required=false`.

The condition is deliberately the SAME one `BearerAuthFilter` uses to hand that id
out, rather than a new "local mode" flag. Tying them together is the point: the row
must exist exactly when, and only when, the filter will use it. When the explicit
flag arrives with 4lve, this condition should FOLLOW it rather than gain a parallel
one — two sources of truth here is how the bug comes back.

Details worth keeping:
- Explicit-id native insert with `ON CONFLICT DO NOTHING`. The column is a serial
  mapped as IDENTITY, so persisting an entity would allocate a different id than the
  one the filter hands out.
- `setval` afterwards. An explicit-id insert leaves the sequence behind, so the next
  ordinary insert (connecting Strava) would reuse the id and die on the primary key —
  a bug that surfaces much later, in someone else's session. Covered by a test.
- `@Priority(Integer.MAX_VALUE)` so it runs after Flyway's own startup observer;
  otherwise it hits a table that does not exist yet.

Verified:
- 5 new tests; full suite 272 passed / 0 failed.
- Disabling the bootstrap makes `aFreshInstallCanActuallyWrite` fail with the exact
  original error, so it is a real regression test rather than a passing decoration.
- A separate profile asserts NOTHING is created when `auth-required=true` (SaaS).
- End to end in a container against a fresh Postgres: `POST /api/tests/result` -> 200
  (it was 500), athlete row present, startup logs "Local mode: created athlete 1".

Follow-up: `backend-v2/scripts/upgrade-test.sh` seeds the athlete row by hand to work
around this, and that seeding must STAY for now — the upgrade test boots the PREVIOUS
release first, and v0.1.0 does not have the fix. It can be removed once the release
being upgraded FROM creates its own athlete.
