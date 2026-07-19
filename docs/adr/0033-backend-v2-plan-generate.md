# 0033 — Backend v2: plan generation (POST /api/plan/generate) (2026-07-18)

Date: 2026-07-18
Status: Accepted
Epic: iron-trainer-eom4 (Phase 7) · Feature: iron-trainer-y7yp · Pattern: ADR 0020 / 0028

## Context

The largest plan slice: `POST /api/plan/generate` builds a full 70.3 season and
saves it as the active plan. It is deterministic at heart (a periodized template)
with an optional LLM adaptation layer — the first plan WRITE ported, and the
first LLM write in the plan vertical (reuses fd31's LangChain4j seam + s6v3's
async envelope).

## What was ported

Faithful ports of `planning/template.py`, `planning/validator.py`,
`service.generate_plan`, and `llm.adjust_season`:

- **`PlanTemplate`** — `buildSeason` (phase assignment, recovery cadence, the
  `1.06^build_index` TSS ramp, `round(hours*60, 0)` target_tss) and `expandWeek`
  (sport split, day layout, `_build_steps` with power/pace/HR targets from
  thresholds, distance estimators, `_describe` with the HR zone spelled out).
- **`PlanValidator`** — `validateSeason` (force 2-week taper, insert recovery
  weeks, cap the +10% ramp, taper/recovery scaling, absolute bounds) returning
  byte-parity adjustment strings; `capWeekWorkouts` (per-sport session caps).
- **`HrZones`** additions — `zoneLabel`, `hrRangeForIntensity`, `INTENSITY_ZONE`.
- **`Nutrition.fuelingNote`** + `PlanResource.applyFueling` — appends the
  one-line fueling note to each workout description (mirrors `_apply_fueling`).
- **Save side** — `Plan.savePlan` (supersede active → insert new active) and
  `PlannedWorkout.saveAll` (replace-all).
- **`PlanAi` / `PlanLlm`** — the guarded LangChain4j seam for `adjust_season`
  (structured `Season` output → merged back to the snake_case season; falls back
  to the template on any failure / no key, exactly like fd31).
- **`PlanResource.generate`** — orchestration: build → optional LLM adapt →
  validate → save plan → expand+cap+fuel every week → save workouts → respond
  `{plan_id, llm_used, weeks, workouts, adjustments, summary}`. `?async=1` runs
  it as a `generate_plan` job (s6v3). DB reads/writes in transactions; the LLM
  call runs OUTSIDE the transaction.

## Parity decisions

- **Banker's rounding everywhere** (`Py.round`/`roundInt`, `Math.rint` in zones)
  — the TSS ramp, planned_tss, power/pace targets, and validator hours all match
  Python `round()` byte-for-byte (verified: `round(262.5)=262`, `round(237.5)=238`).
- **`int()` truncation** for durations (`(int)(h*3600)`), matching Python `int()`.
- **"Today"** = `LocalDate.now()` (UTC in prod/CI), matching `date.today()`.
- **`use_llm` / `async`** parsed via `Params.boolOr` (pydantic-lax; `?use_llm=false`
  and `?async=1` both work — a plain JAX-RS boolean would mis-coerce `1`).

## Testing

- **Java unit tests** (`PlanTemplateTest`) pin `buildSeason`/`expandWeek`/
  `validateSeason` to exact values computed from the Python reference — the
  byte-parity anchors, independent of run date.
- **`@QuarkusTest`** (`PlanGenerateResourceTest`) drives the endpoint end-to-end
  (sync + async job), boots without an LLM key → template fallback.
- **Python `test_plan_generate_parity`** — generate is a non-idempotent write, so
  the test generates `?use_llm=false` on both backends, reads each result back,
  and compares NORMALIZED (dropping volatile `plan_id`/row-id/`created_at`). It's
  appended last so it doesn't disturb the other plan tests. This is the CI gate.

## LLM path (not parity-tested)

`use_llm=true` attempts `adjust_season` via `PlanLlm`; with no key (or any
failure) it falls back to the template and reports `llm_used=false` — so the
endpoint is safe and useful before the key is wired. The LLM output isn't
parity-testable (same category as nutrition regenerate). Follow-up: the season
adaptation may want a larger `max-tokens` than the shared nutrition config;
tune if the structured season truncates in prod.

## Next

Remaining plan slices: `replan-week`, `reconcile`, `checkin` (composes the most
verticals — last). Once shipped + parity-green, `POST /api/plan/generate` can be
added to `PROXY_WRITE_PATHS`.
