# 0045 — Adopt Health Auto Export payload breadth + Grafana (investigation) (2026-07-21)

Date: 2026-07-21
Epic: iron-trainer-udbc (Recovery & Readiness Intelligence) · Related: iron-trainer-yrsz (native HealthKit), iron-trainer-k5d0 (decouple from Strava)
Beans: iron-trainer-mg1n · iron-trainer-yj6a · iron-trainer-rp3t

## Context

Iron Trainer already ingests **Health Auto Export (HAE)** payloads: the iOS app
(or the third-party HAE app) POSTs `{data: {metrics: [...]}}` to
`POST /api/health/ingest`, and `backend-v2` `HealthIngest` maps a **subset** into
`daily_recovery`: `hrv_ms`, `rhr_bpm`, `weight_kg`, `vo2max`, `respiratory_rate`,
`wrist_temp_c`, and sleep (stages + windows).

We investigated the **reference server** for the HAE iOS app —
[`HealthyApps/health-auto-export-server`](https://github.com/HealthyApps/health-auto-export-server)
— to see what the payload can carry that we're dropping, and what's worth
adopting. This ADR records the findings and the adopt/skip decisions. It is an
**investigation ADR**: no code shipped here; the work is tracked in three beans.

## What the reference server is

- **Stack:** TypeScript/Node (Express-style) + **MongoDB** + **Grafana**
  (Infinity plugin querying Mongo). Docker Compose: `server` (:3001), `hae-mongo`
  (:27017), `grafana` (:3000). Auth: `api-key: sk-…` header.
- **One ingest endpoint:** `POST /api/data` with envelope
  `{ data: { metrics?: MetricData[], workouts?: WorkoutData[] } }` — the **same
  shape we already accept** (ours is `/api/health/ingest`).
- **Metric shapes** (`Metric.ts`): most metrics are `BaseMetric {qty, units,
  date, source, metadata}`; `heart_rate` is `{Min, Avg, Max, …}`;
  `sleep_analysis` is `{inBedStart/End, sleepStart/End, core, rem, deep, awake,
  inBed, …}`; `blood_pressure` is `{systolic, diastolic}`. Per-source dedup via a
  unique index on `(date, source)`.
- **Metric catalog** (`MetricName.ts`): ~100 metrics — activity/power, body,
  heart/vitals, respiratory, sleep, mobility, a large nutrition/medical block.
- **Workouts** (`Workout.ts`): `{id, name, start, end, duration, distance,
  activeEnergyBurned, heartRateData[] (Min/Avg/Max), heartRateRecovery[],
  stepCount[], intensity, route[] (GPS lat/lon/speed/altitude/…)}`.
- **Grafana dashboards** (`dashboard-examples/`): **Health Metrics** (sleep
  stacked bar, HR candlestick, active energy, step count, cycling distance),
  **Workout Details** (route geomap + HR timeseries), **Workouts Table**.

## Decision

**Adopt selectively; keep our stack (Quarkus + Postgres).** We do NOT adopt their
MongoDB, Node server, or the Infinity plugin — we already own the ingest path and
a relational store. Three slices:

### 1. Expand the metric mapping (bean `mg1n`, high) — the highest-value port

Map HAE metrics we currently drop, triathlon-first:

- **`cycling_functional_threshold_power` (FTP)** — headline. Auto-update bike FTP
  from HealthKit instead of manual entry; keeps bike power zones current.
- **`cardio_recovery`** (1-min HR recovery) — a readiness/fitness signal.
- **`active_energy` + `apple_exercise_time` + `step_count`** — daily
  (non-workout) activity load.
- **`blood_oxygen_saturation`, `breathing_disturbances`** — optional wellness /
  sleep-quality / altitude signals.

These are `BaseMetric` (or already-handled HR/sleep) shapes — a FIELD-map +
column + Flyway migration extension, with the caveat that FTP is a **latest**
value, not a daily mean.

### 2. Ingest HealthKit **workouts** (bean `yj6a`) — a Strava-independent source

Process the `workouts[]` array we currently ignore. HealthKit workouts carry HR
streams, HR recovery, and GPS routes straight from the athlete's own devices
(Garmin → Apple Health → us), so they give an activity source that **doesn't
depend on Strava** — directly enabling `k5d0` (decouple the planner) and `yrsz`
(native ingestion). Requires a workout model, dedup vs Strava `Activity`
(id / start-time overlap), and a policy for which wins when both exist.

### 3. Grafana for health + training trends (bean `rp3t`, low) — observability

Adopt the dashboards' **ideas** against **our Postgres** (Grafana Postgres
datasource, read-only user), not their Mongo/Infinity stack: recovery trends
(HRV/RHR/VO2max), sleep-stage stacked bar, weight, training load (TSS/CTL/ATL/TSB),
workout-route geomap, recent-workouts table. Internal-only (never expose athlete
data). Complements the BootUI dev console.

## What we deliberately skip

- **MongoDB / Node server / Infinity plugin** — we own the ingest path and use
  Postgres; no second datastore.
- **The full ~100-metric catalog** — the nutrition/medical/mobility long tail
  (dietary vitamins, insulin, handwashing, toothbrushing, …) isn't relevant to
  triathlon readiness. Dietary macros (protein/carbs/water/energy) are a
  *possible* later tie-in to fueling, but not now (YAGNI).
- **Their per-metric Mongo collections** — our relational `daily_recovery` /
  `Activity` / `MetricDaily` model already fits; we extend columns, not schemas.

## Consequences

- The ingest contract stays **backwards-compatible** (same `{data:{metrics,
  workouts}}` envelope, same lenient parsing); we just map more of it.
- FTP auto-update removes a manual-entry footgun and keeps bike zones honest.
- Workout ingestion is the concrete lever for reducing the Strava dependency —
  worth deciding alongside `k5d0`.
- Grafana is additive ops tooling, isolated from the app, read-only on Postgres.
