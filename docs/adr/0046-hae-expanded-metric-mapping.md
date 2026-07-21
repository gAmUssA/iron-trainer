# 0046 — Expanded Health Auto Export metric mapping (FTP, HR recovery, load) (2026-07-21)

Date: 2026-07-21
Bean: iron-trainer-mg1n · Follows: ADR 0045 (HAE investigation) · Epic: iron-trainer-udbc

## Context

ADR 0045 found we already ingest Health Auto Export (HAE) payloads at
`POST /api/health/ingest` but map only a subset. This is the first adoption
slice: map the high-value triathlon metrics we were dropping. FastAPI is
decommissioned, so this is a backend-v2-only change (no FastAPI parity to hold).

## What was built

- **`HealthIngest`** — 6 new metric→column mappings (`FIELD` grew, moved to
  `Map.ofEntries`): `cardio_recovery`→`hr_recovery_bpm`,
  `blood_oxygen_saturation`→`spo2_pct`, `active_energy`→`active_energy_kcal`,
  `apple_exercise_time`→`exercise_min`, `step_count`→`step_count`,
  `cycling_functional_threshold_power`→`cycling_ftp_w`.
  - **Max vs average**: cumulative daily totals (active energy, exercise minutes,
    steps) take the **daily max** (`DAILY_TOTAL_FIELDS`) — the phone re-sends a
    growing daily total as the day progresses and multiple devices each report the
    day's total, so a sum double-counts and an average under-counts; the max is
    the most complete total. Gauges (HR recovery, SpO2, FTP) are averaged.
  - **Unit normalization**: `active_energy` kJ→kcal; SpO2 0–1 fraction→percent
    (mirrors the existing lb→kg / °F→°C conversions).
- **`DailyRecovery`** + **`V2__hae_expanded_metrics.sql`** — 6 nullable
  `double precision` columns; **`HealthResource.recovery`** serializes them.
- **FTP captured, zone-seeding deferred**: `cycling_ftp_w` is stored per day in
  `daily_recovery` (trend/visibility), but this slice does NOT auto-seed
  `Athlete.ftp` / bike zones. Code review flagged that doing it right needs
  latest-by-timestamp (not the daily mean), a guard matching the profile
  validator's FTP bounds (a seeded 1000–2000 W would block profile saves), and a
  delta-sync-safe source-of-truth policy. Split to a focused follow-up
  (`iron-trainer-30m8`), keeping this PR to clean metric capture.

Verified (dev + unit test
`HealthIngestTest.parsesExpandedHaeMetricsMaxAvgAndConversions`): a payload with
all six metrics stores `hr_recovery_bpm=32`, `spo2_pct=97` (from 0.97),
`active_energy_kcal`/`exercise_min`/`step_count` = the re-sent daily max, and
`cycling_ftp_w=250`.

## ⚠️ Health Auto Export iOS config MUST be updated

**These metrics only arrive if the HAE iOS app is configured to export them.**
Unselected metrics simply never appear in the payload (parsed as unknown/ignored
— never an error), so nothing breaks, but the new columns stay null until the
app is updated. In the Health Auto Export app → the automation posting to
`/api/health/ingest` → **Health Metrics** selection, enable (in addition to the
existing HRV, Resting Heart Rate, Weight, VO₂ Max, Respiratory Rate, Wrist
Temperature, Sleep Analysis):

- **Cardio Recovery** (heart-rate recovery)
- **Blood Oxygen Saturation**
- **Active Energy**
- **Apple Exercise Time**
- **Step Count**
- **Cycling Functional Threshold Power (FTP)**

When native HealthKit ingestion ships (bean `yrsz`), the app must emit these same
HK types — the backend contract is unchanged.

## Parity note

`test_health_ingest_recovery_parity` now compares only the fields both backends
emit (the 6 new columns are stripped from the recovery comparison), since FastAPI
is decommissioned and will never grow them. The shared parser behavior still gets
full parity coverage. (Retiring the now-vestigial parity gate against dead FastAPI
code is a separate future cleanup.)

## Consequences

- Cycling FTP, HR recovery, activity load, and SpO2 are now captured per day,
  feeding readiness and the future custom dashboards (bean `rp3t`). Auto-seeding
  bike zones from the captured FTP is the deferred follow-up (`iron-trainer-30m8`).
- The ingest contract stays backwards-compatible; the change is additive.
