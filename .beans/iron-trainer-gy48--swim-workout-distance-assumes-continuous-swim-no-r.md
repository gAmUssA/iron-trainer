---
# iron-trainer-gy48
title: Swim workout distance assumes continuous swim (no rest intervals)
status: todo
type: bug
priority: normal
created_at: 2026-07-17T21:49:44Z
updated_at: 2026-07-17T21:49:44Z
---

User observed generated swim workouts look right time-wise but distance feels too high. Investigation (agent, 2026-07-17): NOT a units bug — pace is sec/100m, distance emitted in meters, consistent across generator/exports/iOS/frontend. Root cause: _est_distance_swim (backend/app/planning/template.py:259) prices the ENTIRE session duration as one continuous swim with no rest intervals, so prescribed meters read high vs a real sets-with-rest pool session. Bike/run are continuous so only swim looks off (matches 'time right, distance wrong'). Secondary ~2%: threshold swims price warmup/cooldown at threshold pace not easy. Fix options: (a) model rest intervals in swim workouts [preferred], (b) swim efficiency factor discounting the estimate, (c) label as continuous-equivalent. Product decision needed.
