---
# iron-trainer-n2n7
title: 'backend-v2: port POST /api/plan/replan-week'
status: completed
type: feature
priority: normal
created_at: 2026-07-19T01:10:29Z
updated_at: 2026-07-19T01:33:18Z
---

Port service.replan_week: regenerate ONE week's workouts (LLM generate_week_workouts or template.expand_week fallback) → validate_week_workouts → apply fueling → replace that week's rows. Response {week_start, llm_used, workouts, notes}. ValueError → HTTP 400. Reuses PlanTemplate.expandWeek + PlanValidator + applyFueling + save (y7yp). Adds PlanAi.generateWeekWorkouts LLM path. Epic 9gjv / milestone 37md.

## Summary of Changes (ADR 0034)
Ported POST /api/plan/replan-week — regenerate one week (LLM or template fallback) → cap → fuel → replace that week's rows. Response {week_start, llm_used, workouts, notes}.
- PlanValidator.capWeekWorkouts now returns WeekResult(workouts, notes) (replan surfaces notes; generate uses .workouts()). PlannedWorkout.replaceWeek (range delete + shared insert). PlanAi.generateWeek + PlanLlm.generateWeekWorkouts LLM seam.
- Errors: no plan / unknown week → 400; missing week_start → 422.
- Tests: @QuarkusTest (replan + 400/422) + Python parity (response + replaced workouts normalized, + 400-parity). v2 suite 137 green.
Remaining plan slices: reconcile, checkin. Flip-eligible after deploy + parity-green.

## Code-review fixes (PR #74)
- #1+#4 (CONFIRMED): guard date+sport non-null/blank in PlanLlm.generateWeekWorkouts → Unavailable → template (re-establishes PR #73 null-guard invariant; prevents NOT-NULL 500 + 'null' cap-note).
- #2 (CONFIRMED): only ABSENT week_start → 422; present-empty flows through → 400 (FastAPI parity).
- #3 (CONFIRMED): applyFueling null description → '' not 'null' (Python 'or ""').
- #5 (cleanup): simplified llm_used (generateWeekWorkouts throws on empty), removed dead isEmpty branch.
v2 suite 137 green.
