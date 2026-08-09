-- Body composition from Health Auto Export (bean qugv): body fat % and BMI.
-- Weight is already captured (weight_kg). Nullable + additive — safe on the
-- existing table; older rows stay NULL.
ALTER TABLE "public"."daily_recovery"
    ADD COLUMN "body_fat_pct" double precision,
    ADD COLUMN "bmi" double precision;
