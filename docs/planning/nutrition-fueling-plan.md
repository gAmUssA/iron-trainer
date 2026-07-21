# Nutrition & Fueling Plan — Detailed Implementation Plan

A new feature that computes per-workout and race-day carbohydrate, hydration, and sodium targets from athlete body weight + session duration/intensity, and translates them into approximate gel counts and a fueling timeline.

---

## Research Basis — The Numbers

### Carbohydrate (during exercise)

Carb intake during exercise is **NOT body-weight dependent** — it's limited by gut absorption rate, which is the same regardless of body size (Jeukendrup, 2014). However, body weight matters for **daily intake**, **pre-race**, and **recovery** targets.

| Duration  | Target carbs/h           | Carb type                                           | Source                      |
|-----------|--------------------------|-----------------------------------------------------|-----------------------------|
| <45 min   | 0 (mouth rinse optional) | —                                                   | Jeukendrup 2014             |
| 45–75 min | 0–30 g/h                 | Any                                                 | Jeukendrup 2014             |
| 1–2 h     | 30–60 g/h                | Single-source OK                                    | ACSM, Burke 2011            |
| 2–2.5 h   | 60–75 g/h                | Single-source OK                                    | Jeukendrup 2014             |
| 2.5–3 h   | 75–90 g/h                | **Multiple transportable** (glucose:fructose 2:1)   | Burke 2011, Jeukendrup      |
| >3 h      | 90–120 g/h               | **Multiple transportable** (0.8:1 fructose:glucose) | Podlogar 2022, Hearris 2022 |

**Key rule:** Above ~60 g/h, must use glucose+fructose blend (multiple transportable carbs) to avoid GI distress. Ratio ~2:1 glucose:fructose (or 0.8:1 fructose:glucose for 120 g/h).

### Hydration (during exercise)

Hydration **IS body-weight dependent** — sweat rate scales with body size, intensity, and temperature.

| Factor                    | Formula / Guideline                                      | Source     |
|---------------------------|----------------------------------------------------------|------------|
| Sweat rate (temperate)    | ~0.5–1.0 L/h for moderate intensity                      | ACSM, NATA |
| Sweat rate (hot/humid)    | ~1.0–2.0 L/h                                             | NATA 2017  |
| Sweat rate range          | 0.5–4.0 L/h (extreme)                                    | NATA       |
| Fluid replacement target  | Replace 80–100% of sweat loss, but **don't gain weight** | ACSM       |
| Max dehydration tolerance | <2% body mass loss                                       | ACSM, NATA |
| Safe drinking ceiling     | <700–750 mL/h to avoid hyponatremia (slower athletes)    | Noakes     |

**Sweat rate estimate by body weight (temperate, moderate intensity):**
- Base estimate: `body_weight_kg × 0.012 L/h` (≈0.8 L/h for 70kg athlete)
- Hot/humid multiplier: ×1.5
- High intensity multiplier: ×1.3
- This gives a starting estimate; athlete can override with measured sweat rate

### Sodium (during exercise)

| Factor                   | Target                                                | Source              |
|--------------------------|-------------------------------------------------------|---------------------|
| Sodium in sweat          | 0.2–1.8 g/h (avg ~1 g/h)                              | NATA, Baker         |
| Replacement target       | 300–700 mg sodium/h                                   | ACSM, Burke         |
| Heavy sweater (>1.2 L/h) | 500–1000 mg sodium/h                                  | Precision Hydration |
| Hyponatremia prevention  | Don't drink plain water beyond sweat rate; add sodium | Noakes              |

### Daily carbohydrate (body-weight dependent)

| Training volume        | Daily carbs    | Source     |
|------------------------|----------------|------------|
| Low/recovery (<1h/day) | 3–5 g/kg/day   | Burke 2011 |
| Moderate (1–3h/day)    | 6–10 g/kg/day  | Burke 2011 |
| Heavy (3–4h/day)       | 8–10 g/kg/day  | Burke 2011 |
| Extreme (>4–5h/day)    | 10–12 g/kg/day | Burke 2011 |

### Pre-race & recovery (body-weight dependent)

| Timing                           | Target                   | Source     |
|----------------------------------|--------------------------|------------|
| 3–4h pre-race meal               | 2–3 g/kg carbs           | ACSM       |
| 1h pre-race                      | 1 g/kg carbs             | ACSM       |
| Post-workout (rapid recovery)    | 1–1.2 g/kg within 30 min | Burke 2011 |
| Carb loading (2–3 days pre-race) | 10–12 g/kg/day           | Burke 2011 |

### Gel equivalents (for "approx number of gels")

Standard gel carb content varies by brand. We'll use a configurable default:

| Gel type                              | Carbs per unit   | Notes                               |
|---------------------------------------|------------------|-------------------------------------|
| Standard gel (GU, SiS GO)             | ~22–25 g         | Single-source or mild blend         |
| High-carb gel (Maurten 160, SiS Beta) | ~40 g            | Multiple transportable, 0.8:1 ratio |
| Default assumption                    | **25 g per gel** | Conservative, most common           |

**Gel count formula:** `ceil(target_carbs_per_hour / gel_carb_grams)` → gels per hour, then × session hours for total.

Example: 90 g/h target ÷ 25 g/gel = **4 gels/h** (or 2.25 Maurten 160 gels/h).

---

## Feature Scope

### 1. Athlete nutrition profile (data model)
- Add `body_weight_kg` field to `Athlete` model (manual entry in Settings tab)
- Add optional nutrition preferences: `gel_carb_g` (default 25 — covers standard vs high-carb gels), `sweat_rate_l_h` (optional override, else estimated), `gi_tolerance` ("low"|"medium"|"high" — affects ramp-up advice)

### 2. Per-workout fueling targets (deterministic)
- Pure function `compute_fueling(workout, profile, conditions)` → returns carb g/h, total carbs, fluid mL/h, total fluid, sodium mg/h, gel count/h, total gels
- Attached to every workout with duration >60 min (shorter sessions get "no fueling needed")
- Shown in workout detail UI (Plan tab) as a fueling card
- Embedded in workout `description` / `notes` for export files

### 3. Race-day nutrition timeline (LLM-generated)
- Uses existing `_call_tool` pattern in `llm.py`
- Generates a timeline: pre-race meal (3–4h before), pre-race snack (1h before), T1, on-bike fueling schedule (every X minutes), T2, on-run fueling/hydration plan
- Validated by a nutrition validator (carb-rate ceiling, hydration safety bounds) — same pattern as `validator.py`

### 4. Nutrition tab in frontend
- Shows race-day timeline as a visual schedule
- Per-workout fueling cards in the Plan tab
- Settings inputs for body weight and nutrition preferences

---

## Validation Results (verified against codebase)

- ✅ **Alembic migrations** live in `backend/alembic/versions/` — latest head is `b2d4e6f8a0c1` (fitness_test_result). New migration chains from it; use the existing `batch_alter_table` pattern for SQLite compatibility.
- ✅ **`repo.save_profile()`** (repo.py:181) has a **hardcoded `fields` list** — new nutrition fields must be added there or they'll be silently dropped.
- ✅ **`athlete_router.py`** — `ProfileUpdate` Pydantic model and `_PUBLIC` tuple both need the new fields; existing `PUT /api/athlete/profile` can carry body weight (no separate nutrition profile endpoint needed for the profile part).
- ✅ **Frontend** — `ProfileEditor` in `Setup.tsx` is the natural home for the body-weight field (reuses `Field` component); `Profile` interface in `api.ts` needs the new fields. Units toggle (`units.tsx`) is mi/km only — weight shown in kg with lbs hint (or convert on the unit toggle).
- ✅ **Tests** — `conftest.py` creates schema via `SQLModel.metadata.create_all` + alembic stamp per test, so new model fields work automatically; the dedicated migration test exercises `alembic upgrade head`, so the migration must be correct.
- ✅ **`routers/__init__.py`** is empty — `main.py` imports router modules directly; just add `nutrition_router` to the import + `include_router` call.
- ✅ **LLM pattern** — `_call_tool` in `planning/llm.py` is reusable as-is for the nutrition schema.
- ✅ **Worktree** ready at `/Users/vikgamov/projects/ai/iron-trainer-nutrition` (branch `feature/nutrition-plan`, based on `9f48300`).

**Simplification vs. earlier draft:** merge the nutrition validator into `nutrition.py` (one module, pure functions) instead of a separate `nutrition_validator.py` — smaller footprint, same testability.

---

## Implementation Steps

### Step 1: Data model + migration
- **File:** `backend/app/models.py` — add fields to `Athlete`: `body_weight_kg: float | None`, `gel_carb_g: float | None` (default 25), `sweat_rate_l_h: float | None`, `gi_tolerance: str | None`
- **New file:** `backend/alembic/versions/<rev>_athlete_nutrition_fields.py` — `down_revision = "b2d4e6f8a0c1"`, `batch_alter_table("athlete")` adding the 4 nullable columns
- **File:** `backend/app/repo.py` — add the new field names to the `fields` list in `save_profile()` (line 183)
- **File:** `backend/app/routers/athlete_router.py` — add fields to `ProfileUpdate` and `_PUBLIC`

### Step 2: Fueling calculation engine (pure functions)
- **New file:** `backend/app/nutrition.py` — pure functions, testable like `validator.py`
  - `estimate_sweat_rate(body_weight_kg, intensity, temp)` → L/h
  - `carb_target_per_hour(duration_s, intensity)` → g/h (uses duration-based table above)
  - `hydration_target_per_hour(sweat_rate_l_h)` → mL/h (80% of sweat rate, capped at safe max)
  - `sodium_target_per_hour(sweat_rate_l_h)` → mg/h
  - `gel_count(carb_g_h, gel_carb_g)` → gels/h (rounded up)
  - `compute_workout_fueling(workout, profile)` → full fueling dict
  - `compute_race_day_plan(profile, race, readiness)` → timeline structure
  - `daily_carb_target(body_weight_kg, weekly_hours)` → g/day
  - `pre_race_meal_target(body_weight_kg)` → g carbs
  - `recovery_target(body_weight_kg)` → g carbs

### Step 3: Safety bounds (in `nutrition.py`, merged — no separate file)
- `validate_fueling(plan)` — caps carb rate at 120 g/h, flags if >60 g/h without MTC note, flags hydration > sweat rate (overhydration risk), flags plain water >750 mL/h without sodium
- Returns `(corrected_plan, adjustments)` — same pattern as `planning/validator.py`

### Step 4: LLM race-day plan generation
- **File:** `backend/app/planning/llm.py` — add `generate_race_day_nutrition(profile, race, readiness, fueling_targets)` using existing `_call_tool` with a new `NUTRITION_PLAN_SCHEMA`
- System prompt: expert sports nutritionist for IRONMAN 70.3, given the deterministic targets as a prior, returns a concrete timeline with times, products, amounts
- Falls back to deterministic `compute_race_day_plan()` if LLM unavailable

### Step 5: Backend API endpoints
- **New file:** `backend/app/routers/nutrition_router.py`
  - `GET /api/nutrition/workout/{workout_id}` — fueling targets for a specific workout
  - `GET /api/nutrition/race-day` — race-day nutrition timeline
  - `POST /api/nutrition/race-day/regenerate` — re-generate with LLM
  - `GET /api/nutrition/daily` — daily carb target based on current training load
  - (body weight + preferences are saved via the existing `PUT /api/athlete/profile`)
- **File:** `backend/app/main.py` — import `nutrition_router` + `include_router` (routers/__init__.py is empty; no change needed there)

### Step 6: Integrate fueling into workout generation
- **File:** `backend/app/planning/template.py` — after `expand_week()`, call `compute_workout_fueling()` for each workout >60min and append fueling info to workout `description`
- **File:** `backend/app/planning/service.py` — include fueling in `generate_plan()` and `replan_week()` flows

### Step 7: Frontend — Nutrition tab + per-workout cards
- **New file:** `frontend/src/components/NutritionView.tsx` — race-day timeline display + daily target + profile editor
- **File:** `frontend/src/components/PlanView.tsx` — add fueling card per workout (expandable)
- **File:** `frontend/src/api.ts` — add nutrition API types and calls
- **File:** `frontend/src/App.tsx` — add "Nutrition" tab to TABS array
- **File:** `frontend/src/styles.css` — styles for fueling cards and timeline

### Step 8: Tests
- **New file:** `backend/tests/test_nutrition.py` — unit tests for pure functions:
  - Carb targets by duration (boundary cases: 45min, 1h, 2h, 2.5h, 3h+)
  - Hydration by body weight (70kg, 55kg, 85kg)
  - Gel count math (90 g/h ÷ 25 g = 4 gels)
  - Sodium by sweat rate
  - Daily carb by training volume
  - Validator: caps, overhydration flag, MTC requirement
  - Race-day plan structure

### Step 9: Verification
- `uv run pytest` in the worktree's `backend/` (all existing + new tests pass)
- `uv run ruff check app` (lint clean)
- `uv run alembic upgrade head` against a copy of the dev DB (migration applies cleanly)
- `npm run build` in `frontend/` (TypeScript compiles)
- Manual smoke: browser preview → set body weight → check fueling cards + Nutrition tab

## Implementation Order

1. Step 2 first (pure `nutrition.py` + Step 3 bounds) with Step 8 tests — TDD, no dependencies
2. Step 1 (model + migration + repo/router wiring)
3. Step 5 (API endpoints) + Step 6 (plan integration)
4. Step 4 (LLM race-day generation)
5. Step 7 (frontend)
6. Step 9 (verification)

All work happens in the worktree: `/Users/vikgamov/projects/ai/iron-trainer-nutrition` (branch `feature/nutrition-plan`).

---

## Example Output (what the athlete sees)

### Per-workout fueling card (long ride, 3h, endurance intensity, 70kg athlete)

```
🍌 Fueling Plan — Long Endurance Ride (3h 00m)
─────────────────────────────────────────────
Carbs:    75 g/h × 3h = 225 g total
          → Use glucose:fructose blend (>60g/h)
Gels:     ~3 standard gels/h (25g each) = 9 gels
          or ~2 high-carb gels/h (40g each) = 6 gels
Fluid:    ~640 mL/h × 3h = 1.9 L total
Sodium:   ~500 mg/h × 3h = 1500 mg total
─────────────────────────────────────────────
Start fueling at min 20. Don't wait until hungry.
```

### Race-day timeline (70.3, 70kg, projected 5h 30m finish)

```
Race Day Fueling Timeline
────────────────────────────────────────────────
04:00  Pre-race meal: 175g carbs (2.5g/kg)
       → Oatmeal + banana + toast + jam
06:00  Pre-race snack: 70g carbs (1g/kg)
       → Energy bar + sports drink
06:45  500mL electrolyte drink (sipping)

Swim (07:00–07:45): No fueling. Last gel 15min before start.

T1: 1 gel + 200mL water

Bike (07:50–10:50, 3h):
  • 90 g/h carbs → 270g total on bike
  • Every 20 min: 1 gel (25g) + 150mL fluid
  • 1 high-carb bottle (80g) + 4 gels
  • Sodium: 500mg/h → 1500mg total

T2: 1 gel + 200mL water

Run (10:55–12:30, 1h35m):
  • 60 g/h carbs → ~95g total
  • Every 15 min: 1 gel or equivalent
  • 4 gels + water at aid stations
  • Sodium: 400mg/h

Post-race: 84g carbs (1.2g/kg) + 25g protein within 30 min
────────────────────────────────────────────────
```

---

## Key Design Decisions

- **Body weight is the only required new input** — everything else has sensible defaults
- **Deterministic-first, LLM-enhanced** — same pattern as plan generation: pure functions compute targets, LLM only generates the narrative timeline
- **Safety validator** — caps carb rate, flags overhydration, enforces MTC above 60 g/h
- **Gel count is approximate** — uses configurable `gel_carb_g` default (25g), shows both standard and high-carb gel options
- **Per-workout fueling is deterministic** (no LLM needed) — just math from duration + intensity + body weight
- **Race-day timeline is LLM-generated** with deterministic targets as the prior — same `_call_tool` pattern as existing plan generation
