# 0035 — Backend v2: reconcile (POST /api/plan/reconcile) (2026-07-19)

Date: 2026-07-19
Status: Accepted
Epic: iron-trainer-eom4 (Phase 7) · Feature: iron-trainer-2t4y · Pattern: ADR 0020 / 0034

## Context

Fifth plan slice: `POST /api/plan/reconcile` folds actual training back into the
plan (matching completed activities to planned workouts) and then re-plans the
next `weeks_ahead` (1-4) upcoming week(s). Composes the already-ported replan
(ADR 0034) + compliance (ADR 0032) with one new piece — the activity↔workout
matcher.

## What was ported

`plan_router.reconcile` + `service.reconcile` + `reconcile.match_workouts`:

- **`Reconcile.matchWorkouts`** — for each PAST planned workout, find the nearest
  unused activity (±1 day, `_MATCHES` sport map, nearest-day-then-biggest-TSS) and
  mark it completed (linking the activity) or skipped (tracked sports); future
  workouts stay planned. Mutates the loaded PlannedWorkout entities inside a
  transaction (Panache flushes on commit — the port of `set_workout_status`).
  Returns `{completed, skipped, upcoming}`.
- **`PlanResource.reconcile`** — match → re-plan the next `weeks_ahead` future
  weeks (via the shared `replanOneWeek`, extracted from the replan endpoint) →
  `{matched, compliance, weeks_replanned, replanned, form_flag}`.

## Decisions

- **weeks_ahead** validated to [1, 4] → **422** (FastAPI `Query(ge=1, le=4)`);
  non-integer → 422 (`Params.intParam`). No active plan → **400**.
- **Matcher determinism:** Brick uses an ordered `[Bike, Run]` list (Python uses a
  set — order-ambiguous only on an exact same-day-and-TSS tie); candidates sorted
  nearest-day then biggest-TSS, first-on-tie (stable, = Python `sorted()[0]`).
- **Reuse:** `replanOneWeek` (shared with the replan endpoint), `Compliance.recent`
  (ADR 0032), `formFlag`, `PlanTemplate.mondayOf`. `next_monday = monday(today)+7`;
  upcoming = weeks with `week_start >= next_monday`, first `weeks_ahead`.
- **"Today"** = `LocalDate.now()` (UTC in prod/CI), matching `date.today()`.

## Testing

- **`ReconcileTest`** (`@QuarkusTest`) pins `matchWorkouts` on fixed dates: a past
  Bike with a same-day activity → completed (+ matched id); a past Run with none →
  skipped; a future Swim → upcoming.
- **`PlanReconcileResourceTest`** — endpoint shape + `weeks_ahead` 0/5/non-int → 422.
- **Python `test_plan_reconcile_parity`** — reconcile `?use_llm=false` on both
  backends; the response has no volatile ids/timestamps and both converge on the
  same state, so it compares byte-for-byte. Plus a `weeks_ahead` 422-parity test.
  CI gate.

## Next

Last plan slice: `POST /api/plan/checkin` — the one-tap composite (Strava sync →
match → reconcile/replan → readiness → tests-due → key sessions → story). Once
shipped + parity-green, the plan writes can be added to `PROXY_WRITE_PATHS`.
