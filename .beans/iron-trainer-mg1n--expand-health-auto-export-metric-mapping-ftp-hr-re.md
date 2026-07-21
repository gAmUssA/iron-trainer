---
# iron-trainer-mg1n
title: Expand Health Auto Export metric mapping (FTP, HR recovery, activity load)
status: todo
type: feature
priority: high
created_at: 2026-07-21T06:23:34Z
updated_at: 2026-07-21T06:23:34Z
parent: iron-trainer-udbc
---

We already ingest Health Auto Export (HAE) payloads at POST /api/health/ingest (backend-v2 HealthIngest) but map only a subset into daily_recovery: hrv_ms, rhr_bpm, weight_kg, vo2max, respiratory_rate, wrist_temp_c, sleep. The HAE payload (per the reference server HealthyApps/health-auto-export-server, MetricName.ts) carries far more that's directly useful for triathlon — port the high-value ones:

- **cycling_functional_threshold_power (FTP)** — headline: auto-update bike FTP from HealthKit instead of manual entry; keeps bike power zones current.
- **cardio_recovery** (1-min heart-rate recovery) — a recovery/fitness signal for readiness.
- **active_energy + apple_exercise_time + step_count** — daily activity/training load (non-workout NEAT).
- **blood_oxygen_saturation**, **breathing_disturbances** (sleep quality) — optional wellness/altitude signals.

Payload shape is BaseMetric {qty, units, date, source, metadata} except HR (Min/Avg/Max) and sleep (already handled). Lenient parsing already exists — just extend the FIELD map + entity + migration.

## Todos
- [ ] Add columns (daily_recovery or MetricDaily): ftp_w, hr_recovery_bpm, active_energy_kj, exercise_min, step_count, spo2_pct (Flyway migration)
- [ ] Extend HealthIngest FIELD map + averaging/aggregation (FTP = latest, not mean)
- [ ] Surface FTP → bike zones (replace/seed manual FTP); readiness uses hr_recovery
- [ ] Parity: keep POST /api/health/ingest lenient + idempotent; contract test
- [ ] ADR update / follow the investigation ADR
