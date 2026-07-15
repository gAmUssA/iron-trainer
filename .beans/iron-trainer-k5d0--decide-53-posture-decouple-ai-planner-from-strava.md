---
# iron-trainer-k5d0
title: 'Decide §5.3 posture: decouple AI planner from Strava-derived data (N4)'
status: todo
type: task
priority: high
created_at: 2026-07-08T17:29:55Z
updated_at: 2026-07-15T02:33:12Z
parent: iron-trainer-cgoy
---

The last open item from the 2026-07-06 review (N4). Both plan generation and race-day nutrition (llm.py) send Strava-derived readiness/context to Claude — in tension with Strava API Agreement §5.3. Options analyzed in docs/research/strava-ingestion-and-ai.md:
- [ ] Viktor decides: (a) feed AI only fitness-test thresholds, (b) export-only zero-API mode, (c) seek written clarification from Strava
- [ ] Implement the chosen option (includes generate_race_day_nutrition)
