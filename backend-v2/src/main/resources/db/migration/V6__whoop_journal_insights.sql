-- WHOOP journal answers (behavior tags from the export's journal_entries.csv) +
-- the persisted AI analysis. Journal rows join whoop_cycles on (athlete_id, date)
-- for behavior→recovery correlations; PK makes re-uploads idempotent upserts.
CREATE TABLE "public"."whoop_journal" (
    "athlete_id" integer NOT NULL,
    "date" character varying NOT NULL,
    "question" character varying NOT NULL,
    "answered_yes" boolean,
    "notes" character varying,
    "updated_at" character varying,
    PRIMARY KEY ("athlete_id", "date", "question")
);

-- One AI analysis per athlete (regenerating overwrites — the LLM call is slow
-- and paid, so the latest result must survive page reloads). runs_date/runs_count
-- rate-limit the paid call to 2 per athlete per UTC day.
CREATE TABLE "public"."whoop_insight" (
    "athlete_id" integer NOT NULL,
    "analysis_md" character varying,
    "created_at" character varying,
    "runs_date" character varying,
    "runs_count" integer,
    PRIMARY KEY ("athlete_id")
);
