# 0042 — Backend v2: PUT /api/athlete/profile (edit thresholds) (2026-07-20)

Date: 2026-07-20
Epic: iron-trainer-eom4 (Phase 7) · Pattern: ADR 0020

## Context

The write half of the athlete-profile vertical (GET shipped in ADR 0040). Editing
thresholds is not a simple setter: new thresholds change the TSS of every past
activity (so the whole PMC), and should propagate to future planned-workout
targets. Port of athlete_router.update_profile + repo.save_profile / recompute_tss
+ planning.service.refresh_future_plan_targets.

## What was built

- **`PUT /api/athlete/profile`** (`ProfileResource`) — validated edit with
  **exclude_unset** semantics: only keys the client SENT are applied (a sent
  `null` clears the field; an unsent field is untouched; unknown keys ignored, as
  pydantic does). Flow: validate → save → recompute TSS → rebuild PMC → return the
  get_profile body → refresh future plan targets when a target field changed.
  - **Validation** mirrors `ProfileUpdate`'s `Field(gt, lt)` bounds (ftp, HRs,
    pace, CSS, hours, weight, gel, sweat) + the `gi_tolerance` enum → **422** on
    violation, wrong type, or a fractional int-field value. Validation runs
    **before** the auth check (FastAPI's pydantic 422 precedes the 401 that
    save_profile → current_athlete_id would raise).
  - **`refresh_future_plan_targets`** (new `PlanTargets` bean) — re-derives every
    FUTURE week's workouts from current thresholds (reusing `PlanTemplate.expandWeek`
    + `PlanValidator.capWeekWorkouts` + fueling + `PlannedWorkout.replaceWeek`);
    past + current-week (week_start ≤ this Monday) untouched. Best-effort: a
    refresh failure logs and degrades to `plan_weeks_refreshed: 0`, never turning a
    committed save into a 500.
  - **`recompute_tss`** re-costs every activity via `Metrics.computeTss` from the
    new thresholds before the PMC rebuild.

## Testing

- **`ProfilePutTest`** (`@QuarkusTest`): apply thresholds → values + `plan_weeks_
  refreshed:0` (no plan); exclude_unset leaves unsent fields; explicit null clears;
  out-of-bounds / bad-enum / fractional-int → 422.
- **Parity** (`test_profile_update_validation_parity`): the 422 cases match on both
  backends. Deliberately **validation-only** — a successful PUT calls
  `rebuild_metrics`, which deletes the directly-seeded `metrics_daily` the
  readiness/pmc parity tests rely on, so a mutating happy-path parity test would
  corrupt the shared-DB harness. The happy path is covered by the v2 unit test.
- Full v2 suite: 181 green; `set_race` + `athlete_profile` routing re-verified
  (ProfileResource is rooted at `/api`, pooling with the sibling /api/athlete/*).

## Remaining for Phase 7

Export ZIP bundles (plan.zip, week/{week_start}.zip) · device pairing (token minting).
