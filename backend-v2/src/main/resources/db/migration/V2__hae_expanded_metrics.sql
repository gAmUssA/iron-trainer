-- Expanded Health Auto Export metrics (bean mg1n): heart-rate recovery, SpO2,
-- daily activity load (active energy, exercise minutes, steps), and Apple's
-- cycling FTP estimate. All nullable — populated only when the HAE iOS app is
-- configured to export the corresponding Health Metrics.
ALTER TABLE "public"."daily_recovery"
    ADD COLUMN "hr_recovery_bpm" double precision,
    ADD COLUMN "spo2_pct" double precision,
    ADD COLUMN "active_energy_kcal" double precision,
    ADD COLUMN "exercise_min" double precision,
    ADD COLUMN "step_count" double precision,
    ADD COLUMN "cycling_ftp_w" double precision;
