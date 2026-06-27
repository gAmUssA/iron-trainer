# 0005 — Fitness Tests library

- **Status:** Accepted
- **Date:** 2026-06-27
- **Deciders:** Viktor + Claude
- **Builds on:** [0002](0002-multi-user-login-with-strava.md),
  [0003](0003-apple-workouts-export-workoutkit.md),
  [0004](0004-device-pairing-live-sync.md)

## Context

Training zones derive from four thresholds (`ftp`, `threshold_hr`,
`threshold_pace_run` sec/km, `css_swim` sec/100m), previously only *inferred* from
Strava history (`analysis.infer_profile`). Athletes need a way to **deliberately
measure** these with standard protocols and re-test every 4–6 weeks so zones stay
honest. The baseline battery: bike 20-min FTP, run 30-min LTHR TT, swim CSS (400/200).

## Decision

Add a **fitness-test library** with two facets per protocol:

1. **Result recorder** — enter raw numbers → compute the threshold → **apply to the
   profile**, reusing the existing `save_profile → recompute_tss → rebuild_metrics`
   cascade (so a measured threshold flows into zones, TSS and the plan exactly like an
   edited one). Compute math: `ftp = round(0.95 × 20-min W)`; `threshold_hr = final-20
   avg HR`, `threshold_pace_run = time/(dist/1000)`; `css_swim = (t400 − t200) / 2`.
2. **Schedulable workout** — `to_workout(slug)` builds a structured session (warmup →
   open-target test effort → cooldown). `POST /api/tests/{slug}/schedule` adds it as a
   `PlannedWorkout` to the active plan, so it **automatically** appears in the Training
   Plan, exports as `.fit`/`.zwo`/`.itw`, and shows in the **iOS app** (which fetches
   `/api/export/plan.itw`) to schedule onto the Apple Watch — no new iOS endpoints.

The **catalog is a Python constant** (`app/fitness_tests.py`) co-locating defs, the
compute functions, and the workout builder. Recorded results are a new
`fitness_test_result` table. Entry is **manual + optional Strava prefill** for
single-activity protocols (bike, run); swim is two TTs → manual. A **new "Tests" tab**
hosts the catalog (with a "due for re-test" badge), record/apply, and a history chart;
plan sessions show a **TEST** badge. Units reused exactly (W / bpm / sec-km / sec-100m).

## Alternatives considered

- **DB-backed catalog table (like the 41-row race catalog)** — rejected: this set is
  3 fixed protocols whose math must live in code anyway; a constant module avoids a
  table + seed and keeps protocol + formula + workout-builder together and testable.
- **Auto-detect tests from Strava only** — rejected as the primary path: a test effort
  isn't always cleanly recorded/synced (esp. swim's two TTs, and "final-20-min HR"
  needs streams). Manual entry is reliable; Strava *prefill* (whole-activity avg) is a
  convenience for bike/run.
- **A separate "test workout" type** distinct from `PlannedWorkout` — rejected: reusing
  `PlannedWorkout` (marked `intensity="test"`, open-target steps) means tests inherit
  export, plan.itw, and iOS scheduling for free.

## Consequences

- (+) Closes the loop: measure → set zones → schedule the next test → re-test-due
  reminder. One source of truth for thresholds (`save_profile`).
- (+) Tests are first-class workouts — schedulable to the Apple Watch via the existing
  iOS flow with zero new mobile plumbing.
- (−) Test workouts use **open** targets (max/steady-hard); `.zwo` (bike-power only)
  renders them as an open/easy step — fine for `.itw`/`.fit`/Watch (the intended path),
  degenerate for TrainingPeaks `.zwo` (non-blocking).
- (−) Strava prefill uses **whole-activity** avg HR, not the protocol's final-20-min
  (editable in the form); swim has no prefill. Noted as future (stream parsing).
- "Due" is a 5-week (35-day) threshold within the 4–6 week guidance.

## Implementation notes

Backend: `app/fitness_tests.py` (catalog + `compute` + `to_workout`),
`FitnessTestResult` model + migration `b2d4e6f8a0c1`, `repo` (`save_test_result`,
`list_test_results`, `apply_test_result`, `last_tested_by_sport`,
`add_test_workout_to_plan`), `routers/tests_router.py` (`/api/tests` catalog · result
· results · apply · `{slug}/schedule` · `{slug}/prefill`). Frontend: `api.ts` test
client, `components/TestsView.tsx` (reuses `Field`/`MiniSpark`/card styles), Tests tab
in `App.tsx`, TEST badge in `PlanView.tsx`. iOS: TEST cue in `WorkoutListView` (test
workouts already ride in `plan.itw`).

## Verification

`tests/test_fitness_tests.py` — compute formulas, record→apply sets the right athlete
field + cascade, due/history, `to_workout` steps, `schedule` → workout in `get_workouts`
+ `plan.itw`, prefill maps bike/run & skips swim. 70 backend tests pass; ruff clean;
migration applies through head. Frontend + iOS build; Tests tab verified in-browser
(record FTP 250 → 238, all three protocols, history spark, due badges).
