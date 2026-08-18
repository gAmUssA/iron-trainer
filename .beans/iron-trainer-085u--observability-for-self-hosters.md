---
# iron-trainer-085u
title: Observability for self-hosters
status: todo
type: epic
priority: normal
created_at: 2026-08-18T16:16:23Z
updated_at: 2026-08-18T16:16:23Z
parent: iron-trainer-sgfg
---

"Is it working, and why is it slow?" for someone running this on their own machine.
Deliberately separate from the Boot UI dev-console bean — that tool disables itself
outside dev mode and is for US, not for self-hosters.

## What already exists
- `/q/health` (Quarkus, incl. a DB check) and `/api/health?deep=1`
- The admin console: jobs, per-kind durations, sync-health telemetry, health-ingest
  audit log, failure trends. This is already a good operator surface — it mostly
  needs to be reachable in local mode (see the local-mode epic)

## Todo
- [ ] Turn the admin console on by default in local mode (it is password-gated by
      `ADMIN_PASSWORD`, which defaults empty — decide what that means locally)
- [ ] Decide whether to ship metrics at all. Micrometer/Prometheus + a Grafana
      container is a real option but doubles the compose footprint for an audience
      that will never open Grafana. Default recommendation: **do not** ship it;
      expose `/q/metrics` for the handful who want to scrape it themselves
- [ ] Make `docker compose logs` genuinely readable — startup should print the URL
      to open, whether Strava/Anthropic are configured, and where the data volume is
- [ ] A single "is everything OK?" panel: last sync, last ingest, job failures,
      DB size — the questions a self-hoster actually has
