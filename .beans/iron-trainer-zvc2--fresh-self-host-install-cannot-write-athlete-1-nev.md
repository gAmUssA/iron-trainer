---
# iron-trainer-zvc2
title: 'Fresh self-host install cannot write: athlete 1 never exists'
status: todo
type: bug
priority: high
created_at: 2026-08-19T14:24:11Z
updated_at: 2026-08-19T14:24:44Z
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
- [ ] Create the default athlete row on first boot in local mode, idempotently
- [ ] Assert it does not run under the %prod SaaS profile
- [ ] Regression test: fresh DB -> POST a write -> 200, not 500
