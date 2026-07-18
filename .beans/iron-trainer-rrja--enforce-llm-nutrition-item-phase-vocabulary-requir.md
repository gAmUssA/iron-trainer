---
# iron-trainer-rrja
title: Enforce LLM nutrition item phase vocabulary + required fields (backend-v2)
status: todo
type: task
priority: normal
created_at: 2026-07-18T19:09:57Z
updated_at: 2026-07-18T19:09:57Z
---

Code-review finding on PR #68 (fd31). The Python NUTRITION_ITEM_SCHEMA constrained the LLM timeline item 'phase' to a 7-value enum (pre_race/swim/t1/bike/t2/run/post_race) and required phase/label/notes. The backend-v2 port (NutritionAi.Item record) uses a plain String phase with no enum and no required-field constraint.

Risk (PLAUSIBLE, model-dependent): if the model returns an off-vocabulary phase (e.g. 'cycling'/'ride' instead of 'bike'), Nutrition.applyLlmTimeline can't match a phase_duration_s (durations keyed by 'bike'/'run'), so validateFueling treats the leg as a duration-less item and applies the meal 300 g / 1000 mL absolute caps instead of the intended per-hour rate check — AND skips the >750 mL/h no-sodium electrolyte warning. Athlete sees a mis-clamped race-day fueling total with a safety note dropped.

Fix options:
- Normalize/validate phase in NutritionLlm.generate (drop or remap unknown phases; log).
- Or enforce via structured-output schema (LangChain4j @Description / enum) so the model is constrained.

The system prompt already lists the phase vocabulary, so this is defense-in-depth. Blocked-by nothing; do alongside future LLM ports (plan generate/checkin) which will share the pattern.
