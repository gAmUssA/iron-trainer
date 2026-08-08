---
# iron-trainer-j41l
title: Admin sync-health telemetry (failure rates, recent failures)
status: todo
type: feature
priority: normal
created_at: 2026-08-08T10:29:35Z
updated_at: 2026-08-08T19:10:30Z
parent: iron-trainer-18n4
---

Investigative dashboard: 'which backend/sync is failing' at a glance.

## Todo
- [x] GET /api/admin/health/jobs → per-kind aggregates over a window: counts by status, failure rate, recent failures (kind, athlete, error, when). (p50/p95 duration deferred — ADR 0058.)
- [x] Frontend: Health tab — failure-rate-per-kind bars + recent-failures feed + 1/7/30d window switch. (Trend sparkline deferred — ADR 0058.)
## Notes
Helps spot systemic sync issues (Strava rate-limit bursts, Apple Health ingest errors) vs one-off. Build after jobs+users so the data shapes are settled. Blocked-by the foundation slice.
