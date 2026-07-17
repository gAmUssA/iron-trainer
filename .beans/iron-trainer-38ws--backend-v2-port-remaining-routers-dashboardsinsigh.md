---
# iron-trainer-38ws
title: 'backend-v2: port remaining routers (dashboards/insights/races)'
status: completed
type: feature
priority: normal
created_at: 2026-07-17T04:45:21Z
updated_at: 2026-07-17T21:49:44Z
parent: iron-trainer-eom4
---

Port dashboards, insights, and races routers to backend-v2 with parity tests.

## Scope (this PR): races vertical
GET /api/races (catalog, filters distance/country/month/q) + PUT /api/athlete/race (select by id or custom). Race entity (table 'race', already in baseline), Athlete race columns (race_id/name/date/distance + cutoff_swim/bike/finish_s, in baseline), cutoffs map {70.3:(4200,19800,30600),140.6:(8400,37800,61200)}, effective_race() with settings defaults (race_name='IRONMAN 70.3 New York', race_date=2026-09-26). Analytics remainder (weekly/trends/activities) is a separate follow-up; race_readiness is bean na5p.

## Summary of Changes
Races vertical ported: GET /api/races (filters) + PUT /api/athlete/race (catalog/custom) with byte parity (8 tests). Race entity, Athlete race columns, cutoffs, effective_race with config defaults. ADR 0024. Analytics-reads remainder (weekly/trends/activities) split into its own bean.
