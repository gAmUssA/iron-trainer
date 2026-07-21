---
# iron-trainer-gy48
title: Swim workout distance assumes continuous swim (no rest intervals)
status: completed
type: bug
priority: normal
created_at: 2026-07-17T21:49:44Z
updated_at: 2026-07-21T21:21:17Z
---

User observed generated swim workouts look right time-wise but distance feels too high. Investigation (agent, 2026-07-17): NOT a units bug — pace is sec/100m, distance emitted in meters, consistent across generator/exports/iOS/frontend. Root cause: _est_distance_swim (backend/app/planning/template.py:259) prices the ENTIRE session duration as one continuous swim with no rest intervals, so prescribed meters read high vs a real sets-with-rest pool session. Bike/run are continuous so only swim looks off (matches 'time right, distance wrong'). Secondary ~2%: threshold swims price warmup/cooldown at threshold pace not easy. Fix options: (a) model rest intervals in swim workouts [preferred], (b) swim efficiency factor discounting the estimate, (c) label as continuous-equivalent. Product decision needed.

## Decision (Viktor, 2026-07-17): APPROVED — add rest intervals
Fix approach (a): model rest intervals in generated swim workouts so prescribed distance reflects actual meters swum in a sets-with-rest session (not a continuous block). Queued to work after ufgr.

## Fixed (2026-07-21)
backend-v2 PlanTemplate: swim main set now split into work + a rest interval (SWIM_REST_FRACTION=0.15, tunable). estDistanceSwim rewritten per-segment: warmup/cooldown at easy (endurance) pace, work at target pace, REST EXCLUDED — so prescribed metres reflect an actual sets-with-rest pool session, not a continuous block. This also fixes the secondary ~2% (warm/cool were priced at target pace). Rest emitted as a 'rest' step (open target) — already a first-class type (iOS renders REST, FitExport→Intensity.REST; swim doesn't export to ZWO). Test: expandWeekSwimModelsRestAndDiscountsDistance (4 steps, rest+open, distance under continuous-at-CSS ceiling). PlanTemplateTest 5/0.
