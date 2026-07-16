---
# iron-trainer-fd31
title: Port nutrition race-day LLM regen to backend-v2 (LangChain4j)
status: todo
type: task
priority: normal
created_at: 2026-07-16T16:29:40Z
updated_at: 2026-07-16T16:29:40Z
---

POST /api/nutrition/race-day/regenerate (+ async job variant) regenerates the race-day fueling timeline via the LLM (llm.generate_race_day_nutrition) then safety-validates. Deferred from the nutrition read-port: it's the LLM path, so it belongs to the LangChain4j planning phase, not the deterministic-math port. Port alongside the other LLM endpoints (plan generate/checkin) once LangChain4j structured output is wired in backend-v2.
