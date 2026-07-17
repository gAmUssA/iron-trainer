---
# iron-trainer-ufgr
title: 'backend-v2: port analytics reads remainder (weekly/trends/activities)'
status: completed
type: feature
priority: normal
created_at: 2026-07-17T21:49:44Z
updated_at: 2026-07-17T22:58:32Z
parent: iron-trainer-eom4
---

GET /api/metrics/weekly, /api/metrics/trends, /api/activities — the analytics_router reads not yet ported (pmc + readiness/today already done). race_readiness is na5p.

## Scope split
This PR: GET /api/activities (list+reverse+limit, count/duplicates) + GET /api/metrics/weekly (weekly_volume: ISO-week bucketing by sport, round(1)). GET /api/metrics/trends (sport_trends + full insights.build, ~500 lines analytics) split to its own bean.

## Summary of Changes
Ported /api/metrics/weekly (weekly_volume) + /api/activities (feed + model_dump). Activity.toDict (23 fields) + raw_json column; Dashboards.weeklyVolume (ISO-week bucketing, sum-of-rounded totals). 4 parity tests, full gate 47/47. ADR 0025. /metrics/trends split to its own bean.
