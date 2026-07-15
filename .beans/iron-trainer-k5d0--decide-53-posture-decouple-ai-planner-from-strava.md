---
# iron-trainer-k5d0
title: 'Decide §5.3 posture: decouple AI planner from Strava-derived data (N4)'
status: todo
type: task
priority: high
created_at: 2026-07-08T17:29:55Z
updated_at: 2026-07-15T02:49:14Z
parent: iron-trainer-cgoy
---

The last open item from the 2026-07-06 review (N4). Both plan generation and race-day nutrition (llm.py) send Strava-derived readiness/context to Claude — in tension with Strava API Agreement §5.3. Options analyzed in docs/research/strava-ingestion-and-ai.md:
- [ ] Viktor decides: (a) feed AI only fitness-test thresholds, (b) export-only zero-API mode, (c) seek written clarification from Strava
- [ ] Implement the chosen option (includes generate_race_day_nutrition)

## New option discovered (2026-07-14): Apple Health as workout source

Health Auto Export's same REST payload carries a workouts array — and Garmin can feed workouts into Apple Health. That's a potential full Strava escape hatch: athlete-owned pipeline (Garmin → Apple Health → their phone POSTs to us), zero Strava API surface, no §5.3 exposure for AI features. Recovery ingestion (clye, in progress) builds the exact endpoint + parsing infrastructure this would need — workout ingestion would be an extension of the same pipeline. Evaluate after clye ships: fidelity of workout data (power/HR streams vs summaries) vs Strava's, and migration story.
