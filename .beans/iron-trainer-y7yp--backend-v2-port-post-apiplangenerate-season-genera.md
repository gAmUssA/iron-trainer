---
# iron-trainer-y7yp
title: 'backend-v2: port POST /api/plan/generate (season generation)'
status: completed
type: feature
priority: normal
created_at: 2026-07-19T00:04:33Z
updated_at: 2026-07-19T00:22:32Z
---

Largest plan slice. Port service.generate_plan: template.build_season (periodization skeleton + TSS ramp) → optional LLM adjust_season (LangChain4j, reuses fd31 pattern; falls back to template on unavailable) → validator.validate_season → template.expand_week per week (+ validate_week_workouts) → nutrition fueling → save plan+workouts. Response {plan_id, llm_used, weeks, workouts, adjustments, summary}; ?async=1 → job (kind generate_plan, s6v3). Deterministic template mode (use_llm=false) is parity-testable. Epic 9gjv / milestone 37md.

## Summary of Changes (ADR 0033)
Ported POST /api/plan/generate (season generation) — the largest plan slice.
- PlanTemplate (buildSeason + expandWeek + all helpers), PlanValidator (validateSeason byte-parity notes + capWeekWorkouts), HrZones additions (zoneLabel/hrRangeForIntensity/INTENSITY_ZONE), Nutrition.fuelingNote, Plan.savePlan + PlannedWorkout.saveAll.
- PlanAi/PlanLlm LangChain4j seam for adjust_season (falls back to template; reuses fd31). PlanResource.generate: build → [LLM] → validate → save → expand+cap+fuel → save; sync + ?async=1 (generate_plan job).
- Tests: PlanTemplateTest (byte-parity value pins), PlanGenerateResourceTest (@QuarkusTest sync+async), Python test_plan_generate_parity (normalized cross-backend, non-idempotent). v2 suite 134 green.
- Banker's rounding + int() truncation + LocalDate.now() today + Params.boolOr for use_llm/async.
Remaining plan slices: replan-week, reconcile, checkin. Flip-eligible (PROXY_WRITE_PATHS) after deploy + parity-green.
