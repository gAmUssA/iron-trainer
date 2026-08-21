-- WHOOP API integration (bean 4a6s), part 2: let API rows and ZIP rows share the
-- whoop_cycles table without either clobbering the other.
--
-- Existing rows all came from the export ZIP, so they are backfilled to 'zip'.
-- The precedence rule (WhoopSync.upsert) is: an 'api' row is never overwritten by
-- a 'zip' row, so a stale export re-upload cannot undo fresher live data. Within
-- 'api', the newer api_updated_at wins; a re-fetch of unchanged data is a no-op.
--
-- This is the WHOOP analogue of the lesson in bean mg1n. There the values were
-- cumulative, so the guard had to be a daily MAX of the VALUE. Here the values are
-- not cumulative, so the guard is a max of the OBSERVATION instead: a write only
-- lands if its (source, timestamp) is not behind what is already stored.
ALTER TABLE "public"."whoop_cycles"
    ADD COLUMN IF NOT EXISTS "source" character varying,
    ADD COLUMN IF NOT EXISTS "api_updated_at" character varying,
    ADD COLUMN IF NOT EXISTS "whoop_cycle_id" bigint;

UPDATE "public"."whoop_cycles" SET "source" = 'zip' WHERE "source" IS NULL;
