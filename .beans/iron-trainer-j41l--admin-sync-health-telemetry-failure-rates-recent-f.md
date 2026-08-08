---
# iron-trainer-j41l
title: Admin sync-health telemetry (failure rates, recent failures)
status: completed
type: feature
priority: normal
created_at: 2026-08-08T10:29:35Z
updated_at: 2026-08-08T19:54:50Z
parent: iron-trainer-18n4
---

Investigative dashboard: 'which backend/sync is failing' at a glance.

## Todo
- [x] GET /api/admin/health/jobs → per-kind aggregates over a window: counts by status, failure rate, recent failures (kind, athlete, error, when). (p50/p95 duration deferred — ADR 0058.)
- [x] Frontend: Health tab — failure-rate-per-kind bars + recent-failures feed + 1/7/30d window switch. (Trend sparkline deferred — ADR 0058.)
## Notes
Helps spot systemic sync issues (Strava rate-limit bursts, Apple Health ingest errors) vs one-off. Build after jobs+users so the data shapes are settled. Blocked-by the foundation slice.

## Summary of Changes

Shipped in PR #107 (squash-merged). GET /api/admin/health/jobs?days=N (@RequireAdmin): per-kind status counts + failure_rate over a window (single group-by query, worst-first) + 20 newest failures. Window is a lexicographic ISO-string compare (PyJson.utcIsoDaysAgo). Frontend: Health tab (default landing) with 1/7/30d switch, per-kind failure bars, recent-failures feed. p50/p95 durations + trend sparkline deferred (see follow-up beans). ADR 0058. Local review (0 findings) + Copilot (3 fixed: HealthView fetch race, failure-bar contrast, phantom 2% fill).
