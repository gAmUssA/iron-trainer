# 0031 — Backend v2: plan read (GET /api/plan) (2026-07-18)

Date: 2026-07-18
Status: Accepted
Epic: iron-trainer-eom4 (Phase 7) · Feature: iron-trainer-qq5f · Pattern: ADR 0020

## Context

`GET /api/plan` (the athlete's active plan + its planned workouts) was still
FastAPI-only — backend-v2 had the `Plan` / `PlannedWorkout` entities (used by the
export + nutrition verticals) but no `/api/plan` route. The write-flip audit
(2026-07-18) surfaced this: `/api/plan` was briefly, wrongly added to
`PROXY_PATHS` and had to be removed because v2 didn't implement it. This is the
first slice of the plan vertical — deterministic, parity-testable — ahead of the
LLM write slices (generate / check-in / replan / reconcile).

## What was ported

`plan_router.get_plan` + `repo.get_active_plan` + `repo.get_workouts`:

- **`PlanResource`** — `GET /api/plan` → `{plan, workouts}`, or
  `{plan: null, workouts: []}` when there is no active plan.
- **`planDict`** mirrors `_plan_dict`: every `Plan` column except `weeks_json`,
  plus `weeks` (the parsed `weeks_json`, `[]` when null).
- **`workoutDict`** mirrors `_workout_dict`: every `PlannedWorkout` column except
  `structure_json`, plus `steps` (parsed, `[]` when null).

## Entity gaps closed (for byte-parity)

The Python `model_dump` shape drove two entity additions:
- `Plan`: added `weeks_json`, `base_weekly_hours`, `created_at`.
- `PlannedWorkout`: added `fit_path`, `zwo_path`, and `matched_activity_id`
  (BigInteger → `Long`; set by reconcile when a session is matched to a Strava
  activity). Without these three, the workout dict would omit keys the FastAPI
  response includes and break parity.

## Parity

- Java `@QuarkusTest` asserts the DTO shape (parsed `weeks`/`steps`, full field
  set, the previously-missing columns present).
- Python `test_plan_parity` (contract suite) compares `GET /api/plan` across both
  backends against the `seeded` fixture's template-generated plan
  (`POST /api/plan/generate?use_llm=false`) — real weeks + workouts, both reading
  the same shared rows. This is the CI gate.

## Review fixes (PR #71)

- **Deterministic tie order.** The template schedules Swim+Bike on the same date,
  and `ORDER BY date` alone leaves ties unspecified → a parity flake between
  SQLModel and Hibernate. Both sides now order by `(date, id)` (= schedule order):
  `repo.get_workouts` and a new `PlannedWorkout.forPlan(aid, planId)` finder shared
  by the plan-read and export verticals (so they can't drift).
- **json.loads parity.** `parseJson` now mirrors `json.loads(x or "[]")` exactly —
  null/empty → `[]`, but a non-empty malformed blob THROWS (→ 500) instead of
  silently returning `[]`, so a corrupt row fails identically on both backends
  (reads have a 5xx local fallback, so the client still gets an answer). Added
  `PyJson.loads` as the read-side counterpart to `PyJson.dumps`.

## Not in this slice

The plan WRITES — `generate` (LLM season, reuses fd31's LangChain4j pattern +
s6v3's async envelope), `checkin`, `replan-week`, `reconcile` — and the
`compliance` read, are later slices. `GET /api/plan` can be added to
`PROXY_PATHS` once this ships and the parity job is green.
