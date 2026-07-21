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
  - **Sum vs average**: cumulative daily totals (active energy, exercise minutes,
    steps) are **summed** within a day; gauges (HR recovery, SpO2, FTP) are
    averaged — a new `SUM_FIELDS` set. (HAE daily-aggregates, so usually one
    sample/day, but summing stays correct at finer intervals.)
  - **Unit normalization**: `active_energy` kJ→kcal; SpO2 0–1 fraction→percent
    (mirrors the existing lb→kg / °F→°C conversions).
- **`DailyRecovery`** + **`V2__hae_expanded_metrics.sql`** — 6 nullable
  `double precision` columns; **`HealthResource.recovery`** serializes them.
- **FTP → bike zones**: `HealthResource.ingest` seeds `Athlete.ftp` from the
  latest day's `cycling_ftp_w` **only when it is currently null** (so power zones
  work out of the box), bumping `athlete.updated_at` for the iOS delta-sync. It
  **never overwrites** a set FTP — a real bike-test value must not be clobbered
  by Apple's estimate. The per-day FTP is still stored in `daily_recovery` for
  trend regardless. (Auto-*replace* with a source/adopt UX is a deliberate
  follow-up, not this slice.)

Verified end-to-end (dev): a payload with all six metrics stores
`hr_recovery_bpm=32, spo2_pct=97 (from 0.97), active_energy_kcal=200 (from
2×418.4 kJ), exercise_min=45, step_count=8452, cycling_ftp_w=250`; `profile.ftp`
seeds to 250 and stays 250 after a re-POST of 999 (never-overwrite). Unit test
`HealthIngestTest.parsesExpandedHaeMetricsSumAvgAndConversions`.

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

- Bike power zones auto-populate for athletes who never set an FTP; existing
  manual FTPs are untouched.
- HR recovery + activity load + SpO2 are now captured per day, feeding readiness
  and the future custom dashboards (bean `rp3t`).
- The ingest contract stays backwards-compatible; the change is additive.
