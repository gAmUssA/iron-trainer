---
# iron-trainer-so2l
title: 'backend-v2: nutrition race-day (compute_race_day_plan + validate_fueling)'
status: todo
type: feature
created_at: 2026-07-18T01:46:14Z
updated_at: 2026-07-18T01:46:14Z
parent: iron-trainer-eom4
---

GET /api/nutrition/race-day: port compute_race_day_plan + validate_fueling, reusing the now-ported race_readiness projection (RaceReadiness) for leg durations. Split from na5p (race_readiness done in PR for iron-trainer). Nutrition vertical follow-up.
