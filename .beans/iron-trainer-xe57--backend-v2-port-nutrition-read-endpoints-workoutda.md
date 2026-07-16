---
# iron-trainer-xe57
title: 'backend-v2: port nutrition read endpoints (workout/daily/race-day)'
status: in-progress
type: feature
priority: high
created_at: 2026-07-16T16:29:40Z
updated_at: 2026-07-16T16:48:17Z
---

Port the 3 deterministic-math nutrition GETs to Quarkus: GET /api/nutrition/workout/{id}, /daily, /race-day. Pure nutrition.py math (compute_workout_fueling, daily_carb_target, pre_race_meal_target, recovery_target, compute_race_day_plan) + profile/race/readiness reads. Parity-gate byte-identical vs FastAPI, then flip via PROXY_PATHS (bearer/iOS). Defer LLM regen POST.



## Scope (PR1)
SHIPPED: GET /api/nutrition/workout/{id} + GET /api/nutrition/daily — pure nutrition.py math, byte-parity verified. /race-day split to [[iron-trainer-na5p]] (needs race_readiness + Activity + clock fix). LLM regen POST deferred → [[iron-trainer-fd31]].
