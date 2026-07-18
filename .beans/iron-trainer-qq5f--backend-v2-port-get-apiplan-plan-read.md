---
# iron-trainer-qq5f
title: 'backend-v2: port GET /api/plan (plan read)'
status: completed
type: feature
priority: normal
created_at: 2026-07-18T21:36:47Z
updated_at: 2026-07-18T22:11:18Z
---

First slice of the plan vertical port. Deterministic two-query read: active Plan + PlannedWorkout rows. Response {plan, workouts} matching FastAPI get_active_plan/get_workouts (_plan_dict: weeks_json→weeks parsed; _workout_dict: structure_json→steps parsed). Both entities exist in v2; needs weeks_json/base_weekly_hours/created_at added to Plan.java. Parity-testable. Foundation for the plan vertical (generate/checkin follow). Also fills the gap found during the write-flip audit (v2 didn't serve /api/plan). Part of epic 9gjv / milestone 37md.

## Summary of Changes (PR #71, merged)

Ported GET /api/plan to backend-v2 (ADR 0031), first slice of the plan vertical.
- PlanResource: GET /api/plan → {plan, workouts} (or {plan:null, workouts:[]}), mirroring plan_router.get_plan.
- planDict = _plan_dict (weeks_json→weeks); workoutDict = _workout_dict (structure_json→steps), full model_dump field set.
- Entity parity gaps closed: Plan += weeks_json/base_weekly_hours/created_at; PlannedWorkout += fit_path/zwo_path/matched_activity_id (BigInteger→Long).
- Review fixes: parseJson mirrors json.loads(x or "[]") (null/empty→[], malformed→500) + PyJson.loads added; deterministic (date,id) workout order on BOTH backends via shared PlannedWorkout.forPlan (repo.get_workouts matched).
- Java @QuarkusTest + Python test_plan_parity (parity CI green). v2 suite 126 green.

Note: bean was authored in the main checkout before the worktree branched, so it was NOT part of PR #71's diff (committed separately post-merge). GET /api/plan is now flip-eligible (add to PROXY_PATHS). Remaining plan slices: compliance read; generate/checkin/replan/reconcile (LLM, reuse fd31+s6v3).
