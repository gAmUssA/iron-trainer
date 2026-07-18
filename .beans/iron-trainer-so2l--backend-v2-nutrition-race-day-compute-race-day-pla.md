---
# iron-trainer-so2l
title: 'backend-v2: nutrition race-day (compute_race_day_plan + validate_fueling)'
status: in-progress
type: feature
priority: normal
created_at: 2026-07-18T01:46:14Z
updated_at: 2026-07-18T03:57:38Z
parent: iron-trainer-eom4
---

GET /api/nutrition/race-day: port compute_race_day_plan + validate_fueling, reusing the now-ported race_readiness projection (RaceReadiness) for leg durations. Split from na5p (race_readiness done in PR for iron-trainer). Nutrition vertical follow-up.

## Summary of Changes

Ported the deterministic GET /api/nutrition/race-day to backend-v2 (byte-parity).

- Nutrition.java: computeRaceDayPlan (leg durations from the RaceReadiness
  projection, per-phase carb/fluid/sodium via the existing fueling helpers,
  pre/post-race meals, MTC gel-blend note) + validateFueling (per-phase rate
  clamps + duration-less item ceilings). Added PLAIN_WATER/transition/meal caps,
  DEFAULT_LEGS_S, and item/projSeconds/num/phaseHours helpers.
- NutritionResource: GET /race-day — assembles profile (Athlete) + effective_race
  + RaceReadiness projection (same shape as FastAPI nutrition_router._readiness).
- Tests: 3 unit tests anchored to a captured FastAPI reference (locks the
  banker's-rounding cases round(187.5)=188 / round(1212.5)=1212 / round(82.5)=82,
  the no-weight path, and a validator clamp) + a race-day parity test
  (seeded_race_day fixture: readiness projection + body weight).

LLM regen (apply_llm_timeline / POST regenerate) is out of scope — bean fd31.

90 backend-v2 + 56 parity tests green.
