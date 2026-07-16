---
# iron-trainer-na5p
title: 'backend-v2: port race_readiness + nutrition race-day'
status: todo
type: feature
priority: normal
created_at: 2026-07-16T16:38:08Z
updated_at: 2026-07-16T16:38:08Z
---

Port dashboards.race_readiness (leg-seconds projection via css_swim / threshold_pace_run / recent bike speed + cutoff checks) to Quarkus, serving BOTH the standalone GET /api/metrics/readiness AND nutrition GET /api/nutrition/race-day (compute_race_day_plan + validate_fueling). Requires: new Activity entity (table activities), a UTC 'today' clock fix (mirror readiness-tz bean for _recent_bike_speed's 84-day cutoff), and heavy f-string/notes byte-parity (summary, per-item notes, offset_min banker's rounds). Highest parity risk of the nutrition set — its own PR. Split from [[iron-trainer-xe57]].
