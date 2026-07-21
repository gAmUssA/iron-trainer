---
# iron-trainer-yj6a
title: Ingest HealthKit workouts as a Strava-independent activity source
status: todo
type: feature
priority: normal
created_at: 2026-07-21T06:23:34Z
updated_at: 2026-07-21T15:41:54Z
parent: iron-trainer-2f2c
---

The HAE payload also carries workouts[] (reference server processes them; we currently ignore them). WorkoutData = {id, name, start, end, duration, distance, activeEnergyBurned, heartRateData[] (Min/Avg/Max), heartRateRecovery[], stepCount[], intensity, route[] (GPS lat/lon/speed/altitude)}. Ingesting these gives an activity source that does NOT depend on Strava — HR streams, HR recovery, and GPS routes straight from HealthKit (Garmin→Apple Health→us, athlete-owned).

Directly supports [[iron-trainer-k5d0]] (decouple the AI planner from Strava-derived data) and [[iron-trainer-yrsz]] (native HealthKit ingestion). Reference dedup pattern: unique index on (date, source).

## Todos
- [ ] Accept workouts[] in POST /api/health/ingest (or a sibling endpoint); parse WorkoutData
- [ ] Map to Activity (or a new HealthWorkout) — source='healthkit', dedup vs Strava (workout id / start-time overlap)
- [ ] Decide: complement Strava vs authoritative when both present (prefer athlete-owned?)
- [ ] Wire into TSS/load + planner inputs so a Strava-less athlete still gets adaptation
- [ ] Contract test with a sample HAE workouts payload
### Blocked-by / needs
- iOS side (yrsz) to actually emit workouts, OR HAE app configured for Workouts export
