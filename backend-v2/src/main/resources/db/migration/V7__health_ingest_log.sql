-- Health-ingest audit log (bean j05e): one row per POST /api/health/ingest, so
-- the admin console can show whether/when Apple Health data arrived (Health Auto
-- Export or the native app), from which client, and what was dropped. No payload
-- stored — just the stats.
CREATE TABLE "public"."health_ingest_log" (
    "id" serial PRIMARY KEY,
    "athlete_id" integer,
    "source" character varying,
    "received_at" character varying,
    "ok" boolean,
    "days_stored" integer,
    "records" integer,
    "unknown_metrics" integer,
    "bad_dates" integer,
    "byte_size" integer,
    "user_agent" character varying,
    "error" character varying
);
CREATE INDEX "ix_health_ingest_log_received_at" ON "public"."health_ingest_log" ("received_at");
CREATE INDEX "ix_health_ingest_log_athlete_id" ON "public"."health_ingest_log" ("athlete_id");
