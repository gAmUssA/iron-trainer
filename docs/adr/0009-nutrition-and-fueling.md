# 0009 — Nutrition & Fueling (per-workout, daily & race-day fueling)

- **Status:** Accepted
- **Date:** 2026-07-06
- **Deciders:** Viktor + Claude
- **Builds on:** [0001](0001-database-abstraction-sqlmodel-alembic.md),
  [0002](0002-multi-user-login-with-strava.md),
  [0005](0005-fitness-tests-library.md)

## Context

Long-course triathlon is famously "the fourth discipline is nutrition" — races are
frequently lost in the gut, not the legs. The plan already produces structured
workouts and a race readiness view, but gave athletes **no fueling guidance**: how
many grams of carbohydrate per hour to take on a given session, how much fluid and
sodium to replace, what to eat daily to support training load, and — most
importantly — a concrete race-day fueling timeline that respects gut limits.

The underlying sports-science is well established and, crucially, *deterministic*:
carbohydrate oxidation during exercise is **gut-limited, not body-weight dependent**
(Jeukendrup 2014: 30–60 g/h up to ~2 h, 60–90 g/h beyond, glucose:fructose blend
required above ~60 g/h, ~120 g/h ceiling); hydration and sodium **do** scale with
body size and sweat rate (ACSM/NATA 2017, Noakes on hyponatremia); daily/pre-race/
recovery carbs scale per-kg (Burke 2011). This is math, not judgement — so the core
must be pure, testable functions, with the LLM used only to make the race-day plan
*concrete and human* on top of a safe numeric prior.

## Decision

Add a **Nutrition & Fueling** feature with a deterministic core and an optional
LLM-enhanced race-day timeline that is **always re-validated** against the same
safety rules.

1. **Pure fueling engine (`app/nutrition.py`).** No I/O, in the style of
   `planning/validator.py`, so every number is cheap to unit-test and easy to audit.
   Guard rails are module constants (`MAX_CARB_G_H = 120`, `MTC_THRESHOLD_G_H = 60`,
   `MAX_FLUID_ML_H = 1000`, `PLAIN_WATER_SAFE_ML_H = 750`, sodium 300–1000 mg/h,
   sweat 0.4–2.5 L/h). It computes per-workout carbs/fluid/sodium/gels, daily/
   pre-race/recovery carbs, and a full race-day plan. A single
   **`validate_fueling()`** clamps any plan back inside the guard rails (and forces a
   multiple-transportable-carb blend above 60 g/h) — this is the safety authority.

2. **Deterministic-first, LLM-enhanced race day.** `compute_race_day_plan()` is the
   default (needs no LLM). `POST /race-day/regenerate` asks the LLM
   (`planning/llm.generate_race_day_nutrition`, a strict tool schema) to turn the
   deterministic targets into a concrete timeline (pre-race meal → snack → swim →
   T1 → bike → T2 → run → recovery, with times/products/amounts). The LLM output is
   overlaid by **`apply_llm_timeline()`**, which *keeps each phase's duration from the
   deterministic prior* and re-runs `validate_fueling()`, so the LLM can never emit an
   unsafe plan.

3. **Manual profile inputs, minimal & optional.** Four new `Athlete` fields —
   `body_weight_kg` (the only meaningful new input; gates daily/hydration math),
   `gel_carb_g` (defaults to 25 g), `sweat_rate_l_h` (measured override, else
   estimated), `gi_tolerance`. Persisted via the existing `save_profile` path and the
   `PUT /api/athlete/profile` allowlist, edited in the existing **Thresholds** editor.

4. **Fueling flows into the plan automatically.** During plan generation and replan,
   `planning/service._apply_fueling()` attaches per-workout fueling and appends a
   one-line fueling note to each fuel-worthy (>~45 min) workout's description, so it
   also rides into exports (`.fit`/`.zwo`/`.itw`) and the iOS app with no new plumbing.

5. **New API + UI surface.** `routers/nutrition_router.py`
   (`/workout/{id}`, `/daily`, `/race-day`, `/race-day/regenerate`). A new **Nutrition
   tab** (`components/NutritionView.tsx`) shows daily targets + the race-day timeline
   with an **AI regenerate** button; per-workout **expandable fueling cards** are added
   to `PlanView.tsx`.

## Alternatives considered

- **LLM-only fueling (no deterministic core)** — rejected: fueling is safety-critical
  (hyponatremia, GI failure) and the physiology is deterministic. An LLM alone can
  hallucinate unsafe carb/fluid loads. The engine is the source of truth; the LLM only
  makes it concrete, and its output is clamped back through `validate_fueling()`.
- **Deterministic-only (no LLM at all)** — rejected as the *only* option: the numeric
  plan is correct but terse. The LLM adds real product names, timing and a coach-like
  narrative athletes will actually follow — but it is opt-in per regenerate and never
  the safety authority.
- **Scale race-carbs by body weight** — rejected: carbohydrate oxidation is
  gut-limited, not per-kg; scaling carbs by weight is a common but wrong model. Only
  hydration/sodium and daily/pre-race/recovery carbs scale with body size.
- **A dedicated nutrition table / new athlete sub-entity** — rejected: four nullable
  columns on `Athlete` reuse the existing profile save/allowlist/migration cascade with
  no new persistence surface; targets themselves are computed, not stored.

## Consequences

- (+) Safe by construction: every plan (deterministic or LLM) passes through one
  validator with explicit, sourced guard rails; the LLM cannot ship an unsafe plan.
- (+) Works with zero configuration (deterministic) and degrades gracefully — if the
  LLM/Anthropic key is unavailable, regenerate returns the deterministic plan with a
  note instead of failing.
- (+) Fueling notes ride existing export/iOS paths automatically (in the workout
  description), so watch/TrainingPeaks users see fueling with no new endpoints.
- (−) `body_weight_kg` is manual and un-nudged today; daily/hydration targets are
  blank until it's entered (surfaced as a hint in the daily response).
- (−) Sweat rate is *estimated* from body weight/intensity/temperature unless the
  athlete measures and overrides it; temperature isn't yet sourced per-race.
- (−) `gi_tolerance` is captured but only lightly used (LLM context) so far — room to
  tune carb ceilings per athlete later.

## Implementation notes

Backend: `app/nutrition.py` (pure engine + `validate_fueling` + `apply_llm_timeline`),
`Athlete` fields + migration `c3e5f7a9b1d2`, `repo.save_profile` + `athlete_router`
allowlist, `planning/llm.generate_race_day_nutrition` (+ `NUTRITION_PLAN_SCHEMA`),
`planning/service._apply_fueling` (generate + replan), `routers/nutrition_router.py`
registered in `main.py`. Frontend: `api.ts` nutrition types/client, `NutritionView.tsx`,
Nutrition tab in `App.tsx`, expandable fueling cards in `PlanView.tsx`, profile inputs
in `Setup.tsx`, `styles.css`.

## Verification

`tests/test_nutrition.py` — per-hour/total carbs, MTC blend above 60 g/h, sweat/fluid/
sodium clamping, daily/pre-race/recovery per-kg, `validate_fueling` re-clamps an unsafe
plan, and `apply_llm_timeline` carries phase durations + clamps unsafe LLM numbers and
falls back to base items when empty. Backend: 117 tests pass; ruff clean; `alembic
upgrade head` applies through `c3e5f7a9b1d2` on a fresh SQLite DB. Frontend `tsc -b &&
vite build` compiles clean.
