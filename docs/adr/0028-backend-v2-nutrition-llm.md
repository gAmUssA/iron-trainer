# 0028 — Backend v2: nutrition race-day LLM regenerate (2026-07-18)

Date: 2026-07-18
Status: Accepted
Epic: iron-trainer-eom4 (Phase 7) · Feature: iron-trainer-fd31 · Pattern: ADR 0020

## Context

`POST /api/nutrition/race-day/regenerate` is the first LLM-backed endpoint in
backend-v2. FastAPI builds a deterministic race-day fueling plan
(`compute_race_day_plan`, ported in bean so2l), then asks Claude for a concrete
timeline and overlays it on that plan, re-clamping for safety. This is the first
use of an LLM from Quarkus and establishes the reusable LangChain4j pattern for
the remaining AI ports (plan generation, weekly check-in).

## What was ported

`nutrition_router._regenerate_race_day` + `nutrition.apply_llm_timeline`:

- **`NutritionAi`** — a LangChain4j `@RegisterAiService` interface. `@SystemMessage`
  (expert sports-nutritionist prompt, per-hour safety ceilings) + `@UserMessage`
  (profile / race / readiness / deterministic prior). Returns a structured
  `Timeline` record (structured output, not Anthropic tool-use). Model
  `claude-sonnet-4-6`, max-tokens 4000, timeout 60s — FastAPI planner parity.
- **`NutritionLlm`** — the guarded seam mirroring `LLMUnavailable`. `available()`
  gates on a real key; `generate()` throws `Unavailable` when unavailable or when
  the model call fails (or returns a null timeline). Converts `Timeline` → the
  snake_case item maps the rest of the code works with.
- **`Nutrition.applyLlmTimeline`** — overlays the LLM timeline on the base plan,
  inheriting each phase's `phase_duration_s` so `validateFueling` still
  rate-checks the model's amounts, then re-clamps and sets `llm_used`/`summary`/
  `adjustments`. Does not mutate the base.
- **`NutritionResource`** — `raceDay()` refactored to a shared `assemble()`; the
  POST reads in a transaction and calls the LLM **outside** it (external I/O, up
  to 60s — the 28s-incident pattern). LLM unavailable → deterministic plan with
  an "LLM unavailable — showing the deterministic plan." note prepended.

## Decisions

- **Boot without a key via a non-empty sentinel.** The langchain4j Anthropic
  extension REJECTS an empty api-key at boot (config validation → the app won't
  start). So the key defaults to a non-empty sentinel `no-key` when
  `ANTHROPIC_API_KEY` is unset; `NutritionLlm.available()` treats the sentinel as
  "no key" and falls back. This is what lets backend-v2 deploy BEFORE the key is
  wired without the silent healthcheck-crash (see the backend-v2 Railway deploy
  trap). Ops ceiling: `${VAR:default}` only applies when the var is absent — a
  present-but-empty var still crashes boot, so never set the var blank.
- **The LLM is always optional.** Every path degrades to the deterministic plan;
  the endpoint never 500s on a model failure (a null structured-output timeline
  is wrapped as `Unavailable`, not left to NPE past the fallback).
- **Key wiring.** `ANTHROPIC_API_KEY` on backend-v2 is a cross-service reference
  to the FastAPI service (`${{ iron-trainer.ANTHROPIC_API_KEY }}`), like
  `SESSION_SECRET`, so the two stay in sync and the value is never handled.

## Parity notes

- `applyLlmTimeline` is a faithful port: `durations` keyed by phase where
  `phase_duration_s` is truthy (last-wins); `plan["items"] = items or base items`;
  `summary` set only when non-empty; `validateFueling` re-clamp → `adjustments`.
- The async `?async=1` job variant was deferred to bean s6v3 (async envelope) and
  is delivered there — see ADR 0029.

## Status of the endpoint

Dormant behind the strangler: not in `proxy_write_paths`, so FastAPI still serves
race-day regenerate. It must NOT be flipped to backend-v2 until the async
envelope (ADR 0029) is in place, or the frontend's `?async=1 → {job}` contract
breaks.

## Follow-ups

- `iron-trainer-rrja` — enforce the LLM item `phase` vocabulary (Python had a
  7-value enum; the Java record uses a plain String), so an off-vocabulary phase
  can't defeat the duration-aware clamp.
