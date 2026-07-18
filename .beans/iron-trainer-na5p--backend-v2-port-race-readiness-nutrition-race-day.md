---
# iron-trainer-na5p
title: 'backend-v2: port race_readiness + nutrition race-day'
status: completed
type: feature
priority: normal
created_at: 2026-07-16T16:38:08Z
updated_at: 2026-07-18T01:46:14Z
---

Port dashboards.race_readiness (leg-seconds projection via css_swim / threshold_pace_run / recent bike speed + cutoff checks) to Quarkus, serving BOTH the standalone GET /api/metrics/readiness AND nutrition GET /api/nutrition/race-day (compute_race_day_plan + validate_fueling). Requires: new Activity entity (table activities), a UTC 'today' clock fix (mirror readiness-tz bean for _recent_bike_speed's 84-day cutoff), and heavy f-string/notes byte-parity (summary, per-item notes, offset_min banker's rounds). Highest parity risk of the nutrition set — its own PR. Split from [[iron-trainer-xe57]].

## Summary of Changes
Ported race_readiness → GET /api/metrics/readiness: RaceReadiness (leg projections swim/bike/run, recentBikeSpeed 84d, transitions, total, cumulative cutoff checks). Fixed null-distance (raceless athlete) via String.valueOf (Map.of null-hostile). 1 parity test + smoke test; gate 55/55. ADR 0027. Completes all analytics_router reads. Nutrition race-day split to its own bean.
