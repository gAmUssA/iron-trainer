---
# iron-trainer-mg1n
title: Expand Health Auto Export metric mapping (FTP, HR recovery, activity load)
status: completed
type: feature
priority: high
created_at: 2026-07-21T06:23:34Z
updated_at: 2026-07-21T07:38:39Z
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

## Implemented 2026-07-21 — ADR 0046
- 6 new FIELD mappings in HealthIngest (cardio_recovery, blood_oxygen_saturation, active_energy, apple_exercise_time, step_count, cycling_functional_threshold_power) + DailyRecovery columns + V2 Flyway migration + recovery() serialization.
- SUM_FIELDS (active_energy_kcal, exercise_min, step_count) summed; gauges averaged. kJ→kcal + SpO2 fraction→% conversions.
- FTP → Athlete.ftp: SEED only when null (never overwrite a real bike-test value with Apple's estimate); bumps updated_at for iOS zone sync. Per-day FTP also stored in daily_recovery for trend.
- Parity test relaxed to ignore the 6 backend-v2-only recovery fields (FastAPI decommissioned).
- Unit test + dev end-to-end verified (fields persist; FTP seeds to 250; re-POST 999 stays 250).

## ⚠️ ACTION: update Health Auto Export iOS config
The new metrics only arrive if the HAE app is set to EXPORT them. In the HAE app's automation → Health Metrics, enable: Cardio Recovery, Blood Oxygen Saturation, Active Energy, Apple Exercise Time, Step Count, Cycling FTP. (Unselected = absent, never an error.) See ADR 0046. Same HK types needed when native ingestion (yrsz) ships.

### Deferred follow-ups
- FTP auto-*replace* (source/adopt UX) — not just seed-if-null.
- readiness scoring consuming hr_recovery.

## Code-review fixes 2026-07-21
- [CONFIRMED-ish] Cumulative fields (active_energy/exercise/steps) were SUMMED → double-counts re-sent daily totals + multi-source. Changed to **daily MAX** (DAILY_TOTAL_FIELDS).
- [PLAUSIBLE ×4 on the FTP seed] Deferred the Athlete.ftp auto-seed to [[iron-trainer-30m8]] — doing it right needs latest-by-timestamp (not mean), bounds matching the profile validator (a seeded 1000-2000W blocks profile saves), and a delta-sync-safe policy. This slice now only CAPTURES cycling_ftp_w in daily_recovery. Removed seedFtpIfUnset/latestFtp + Athlete import.
- Unit test updated to MAX semantics; all pass.

## Summary of Changes 2026-07-21
Shipped (PR #89, ADR 0046). backend-v2 now maps 6 more HAE metrics into daily_recovery: hr_recovery_bpm, spo2_pct, active_energy_kcal, exercise_min, step_count, cycling_ftp_w. Cumulative metrics take the daily MAX (not sum — avoids double-counting re-sends/multi-source); kJ→kcal + SpO2 fraction→% conversions. FTP→bike-zones auto-seed DEFERRED to [[iron-trainer-30m8]] after review (bounds/latest/sync subtleties). Parity harness patched to add the columns to the shared DB (Flyway off in the prod jar). PROD Supabase DB manually ALTERed before cutover (Flyway off in prod — see memory). Deploy verified: recovery endpoint 401 not 500.
ACTION for Viktor: enable the metrics in the Health Auto Export iOS app (Cardio Recovery, Blood Oxygen, Active Energy, Apple Exercise Time, Step Count, Cycling FTP).
