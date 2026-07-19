# 0034 — Backend v2: replan one week (POST /api/plan/replan-week) (2026-07-19)

Date: 2026-07-19
Status: Accepted
Epic: iron-trainer-eom4 (Phase 7) · Feature: iron-trainer-n2n7 · Pattern: ADR 0020 / 0033

## Context

Fourth plan slice: `POST /api/plan/replan-week` regenerates a SINGLE week's
workouts on demand (e.g. after a change in fitness/feel) and replaces just that
week's rows. Reuses the generation engine from ADR 0033 (template expander,
validator, fueling, save) plus a new LLM week-generation seam.

## What was ported

`plan_router.replan_week` + `service.replan_week`:

- **`PlanResource.replanWeek`** — find the week in the active plan by
  `week_start`; if `use_llm`, try `PlanLlm.generateWeekWorkouts` (falls back to
  `PlanTemplate.expandWeek` on unavailable/empty); `capWeekWorkouts` (now
  returning its notes); `applyFueling`; `replaceWeek`. Response
  `{week_start, llm_used, workouts, notes}`.
- **`PlanValidator.capWeekWorkouts`** now returns `WeekResult(workouts, notes)` —
  replan surfaces the cap notes (generate still discards them via `.workouts()`).
- **`PlannedWorkout.replaceWeek`** — range-delete `[week_start, week_end]` + insert
  (save_workouts replace_all=False); the insert loop is shared with `saveAll`.
- **`PlanAi.generateWeek` / `PlanLlm.generateWeekWorkouts`** — the LangChain4j
  seam for `generate_week_workouts` (structured `WeekWorkouts` → snake_case maps
  with steps; falls back to the template on any failure).

## Decisions

- **Error mapping:** no active plan / unknown week → `ValueError` → **400**
  (`BadRequestException`), matching the FastAPI route. A missing `week_start`
  query param → **422** (FastAPI required-param behavior).
- **Profile keys** for replan match Python exactly (ftp, threshold_hr, max_hr,
  threshold_pace_run, css_swim — no weekly_hours_target).
- **week_end** = `week_start + 6 days`; `LocalDate.now()`-free (operates on the
  stored plan week).
- **Fueling** reuses the shared `applyFueling` (now taking the fuel fields
  directly, so generate + replan share it).

## Testing

- **`@QuarkusTest`** — generate a plan → replan its first week (template mode) →
  correct response; unknown week → 400; missing week_start → 422.
- **Python `test_plan_replan_week_parity`** — replan `?use_llm=false` on both
  backends (non-idempotent; replaces the week), comparing the response AND the
  replaced week's workouts NORMALIZED (drop id/plan_id/created_at). Plus a
  400-parity test for an unknown week. CI gate.

## LLM path (not parity-tested)

`use_llm=true` attempts `generate_week_workouts`; no key / any failure / empty →
template fallback, `llm_used=false`. Same category as the other LLM ports
(dormant until the key is wired; the validator caps the output regardless).

## Next

Remaining plan slices: `reconcile`, then `checkin` (composes the most verticals —
last). Once shipped + parity-green, the plan writes can be added to
`PROXY_WRITE_PATHS`.
