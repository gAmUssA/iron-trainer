-- WHOOP export-ZIP import: one row per athlete-day from physiological_cycles.csv.
-- Deliberately a SEPARATE table from daily_recovery — WHOOP Recovery %/Strain are
-- proprietary scores with no HealthKit equivalent, and WHOOP HRV is RMSSD while
-- HealthKit HRV is SDNN (never merge; see bean iron-trainer-ids6). Composite PK
-- makes re-uploading an export an idempotent upsert.
CREATE TABLE "public"."whoop_cycles" (
    "athlete_id" integer NOT NULL,
    "date" character varying NOT NULL,
    "cycle_start" character varying,
    "cycle_end" character varying,
    "recovery_score" double precision,
    "hrv_rmssd_ms" double precision,
    "rhr_bpm" double precision,
    "day_strain" double precision,
    "energy_kcal" double precision,
    "spo2_pct" double precision,
    "skin_temp_c" double precision,
    "sleep_performance_pct" double precision,
    "sleep_efficiency_pct" double precision,
    "respiratory_rate" double precision,
    "asleep_h" double precision,
    "updated_at" character varying,
    PRIMARY KEY ("athlete_id", "date")
);
