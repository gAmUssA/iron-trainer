---
# iron-trainer-fd31
title: Port nutrition race-day LLM regen to backend-v2 (LangChain4j)
status: in-progress
type: task
priority: normal
created_at: 2026-07-16T16:29:40Z
updated_at: 2026-07-18T19:10:11Z
---

POST /api/nutrition/race-day/regenerate (+ async job variant) regenerates the race-day fueling timeline via the LLM (llm.generate_race_day_nutrition) then safety-validates. Deferred from the nutrition read-port: it's the LLM path, so it belongs to the LangChain4j planning phase, not the deterministic-math port. Port alongside the other LLM endpoints (plan generate/checkin) once LangChain4j structured output is wired in backend-v2.

## Summary of Changes

Ported `POST /api/nutrition/race-day/regenerate` (sync) to backend-v2 via LangChain4j.

- **NutritionAi** (`@RegisterAiService`): system+user prompt, structured `Timeline` output (port of planning/llm.generate_race_day_nutrition).
- **NutritionLlm** (`@ApplicationScoped`): guarded seam. `available()` gates on a real key; throws `Unavailable` (→ deterministic fallback) when unset or the call fails. Converts Timeline → snake_case item maps.
- **Nutrition.applyLlmTimeline**: faithful port of apply_llm_timeline — overlays the LLM timeline on the deterministic base, inherits each phase's duration so validateFueling still clamps, re-clamps, sets llm_used/summary/adjustments. Does not mutate base.
- **NutritionResource**: `raceDay()` refactored to a shared `assemble()`; new POST endpoint reads in a tx, calls the LLM OUTSIDE the tx, merges or falls back.
- **Config**: langchain4j-anthropic extension; model=claude-sonnet-4-6, max-tokens=4000, timeout=60s. Key defaults to non-empty sentinel `no-key` so the app BOOTS without ANTHROPIC_API_KEY (langchain4j rejects an empty key at boot — silent-crash trap); available() treats the sentinel as no-key.
- **Tests**: applyLlmTimeline unit tests + @QuarkusTest proving boot-without-key, the LLM merge path (mocked NutritionLlm), and the fallback path. Full suite 114 green.

**Deferred:** async `?async=1` job variant → belongs to the async-envelope vertical (s6v3). Parity test for regenerate skipped — the deterministic base is already parity-gated via GET /race-day; the fallback only prepends one note. ANTHROPIC_API_KEY to be added to backend-v2 Railway to activate the live LLM path.

## Code-review fixes (PR #68)

High-effort multi-agent review surfaced 6 PLAUSIBLE findings; triaged:
- FIXED #2: null structured-output Timeline now throws Unavailable (was NPE escaping the fallback → 500). Endpoint never-500 contract test added.
- FIXED #5: json() logs on serialization failure instead of silently sending {}.
- DOCUMENTED #1: empty-string ANTHROPIC_API_KEY (present-but-blank) defeats the sentinel and crashes boot — added an OPS CEILING comment (set a real key or leave unset, never blank). Low risk: regenerate is not proxied (proxy_write_paths empty), so a backend-v2 boot failure has no user impact and deploy-check catches it.
- DEFERRED #3: ?async=1 job envelope → s6v3. NOT a live break — regenerate is not in proxy_write_paths, so FastAPI still serves the async path. **FLIP DEPENDENCY: do NOT add /api/nutrition/race-day/regenerate to proxy_write_paths until the async envelope (s6v3) lands, or the frontend Regenerate button (viaJob → {job}) breaks.**
- FOLLOW-UP #4: phase-vocabulary enforcement → bean rrja.
- SKIPPED #6: sentinel-literal duplication (cosmetic; can't reference a Java constant from application.properties).
