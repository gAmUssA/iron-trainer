-- PROVISIONAL baseline: probe table for the Phase-1 skeleton.
-- MUST be replaced by a full pg_dump --schema-only baseline (with
-- baseline-on-migrate) before the first vertical that shares tables with
-- the live FastAPI app. Tracked in bean iron-trainer-jedh.
-- Note: real tables use the FastAPI schema's identity columns; this probe
-- follows Hibernate's default pooled-lo sequence convention instead.
CREATE TABLE IF NOT EXISTS athlete_v2_probe (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255),
    ftp DOUBLE PRECISION,
    threshold_hr INTEGER,
    created_at TIMESTAMP DEFAULT now()
);
CREATE SEQUENCE IF NOT EXISTS athlete_v2_probe_seq INCREMENT BY 50;
