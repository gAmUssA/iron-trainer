-- Provenance for the daily recovery signal (bean aydv): the source (HRV bundle id,
-- e.g. Apple Watch vs WHOOP) the client selected for the night. Lets the app label
-- readiness by origin so a WHOOP-vs-Apple overlay is a genuine two-source compare.
-- Nullable + additive — safe on the existing table; older rows stay NULL.
ALTER TABLE "public"."daily_recovery"
    ADD COLUMN "source" text;
