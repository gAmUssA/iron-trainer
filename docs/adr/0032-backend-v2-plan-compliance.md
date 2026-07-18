# 0032 — Backend v2: plan compliance (GET /api/plan/compliance) (2026-07-18)

Date: 2026-07-18
Status: Accepted
Epic: iron-trainer-eom4 (Phase 7) · Feature: iron-trainer-gu6p · Pattern: ADR 0020

## Context

Second slice of the plan vertical (after `GET /api/plan`, ADR 0031).
`GET /api/plan/compliance` reports planned-vs-actual training per ISO week plus a
recent-window summary. Deterministic (no LLM), so it's parity-testable now.

## What was ported

`plan_router.compliance` + `reconcile.compliance_by_week` + `reconcile.recent_compliance`:

- **`Compliance.byWeek(workouts, acts)`** — groups the plan's workouts by ISO week
  (Monday start), summing planned/actual TSS + hours and counting
  completed/skipped/planned. `actual` comes from the workout's
  `matched_activity_id` looked up in the athlete's non-duplicate activities. Float
  fields rounded to 1 dp (banker's, `Py.round`).
- **`Compliance.recent(workouts, acts, today, days=21)`** — compact
  planned-vs-actual over the last 21 days (inclusive), with `completion_rate` and
  `load_ratio` (both `round(_, 2)` or null), `planned_tss`/`actual_tss`
  (`round(_)` → int via `Py.roundInt`).
- **`PlanResource.compliance`** — `GET /api/plan/compliance` → `{weeks, recent}`,
  or `{weeks:[], recent:null}` with no active plan.

## Parity decisions

- **Activity set:** non-duplicate only (`isDuplicate = 0 or null`), matching
  `repo.list_activities()`'s default (`include_duplicates=False`).
- **Date parsing:** `Iso.parseDate` (mirrors `datetime.fromisoformat(...).date()`,
  null on unparseable) for the `_day` helper; week start = `d - (weekday)`.
- **"Today":** `LocalDate.now()` (system TZ = UTC in the prod/CI containers,
  matching FastAPI `date.today()`), consistent with the parity-green TrendsResource.
- **Workout order:** `PlannedWorkout.forPlan` (the shared `(date, id)` finder from
  ADR 0031) — irrelevant to the sums but keeps the plan vertical consistent.

## Tests

- Java `@QuarkusTest` pins the per-status math: a completed workout (matched to an
  activity), a skipped, and a planned one → correct week counts, `actual_tss`/
  `actual_hours` from the matched activity, and the `recent` ratios
  (`completion_rate` `round(1/3,2)=0.33`, `load_ratio` `round(55/150,2)=0.37`).
- Python `test_plan_compliance_parity` compares both backends over the `seeded`
  template plan — the CI gate.

## Next

Remaining plan slices: `generate` (LLM season, reuses fd31 + s6v3), `replan-week`,
`reconcile`, `checkin`. Once shipped + parity-green, `GET /api/plan/compliance`
can be added to `PROXY_PATHS`.
