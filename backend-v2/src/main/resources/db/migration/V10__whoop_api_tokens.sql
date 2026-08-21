-- WHOOP API integration (bean 4a6s), part 1: OAuth token storage.
--
-- Mirrors the strava_* token columns on athlete. Nullable throughout: an athlete
-- who never connects WHOOP, and every self-host install that does not configure
-- credentials, must be unaffected.
--
-- whoop_user_id is stamped at connect time so a later reconnect to a different
-- WHOOP member can be detected rather than silently merging two people's data.
ALTER TABLE "public"."athlete"
    ADD COLUMN IF NOT EXISTS "whoop_refresh_token" character varying,
    ADD COLUMN IF NOT EXISTS "whoop_access_token" character varying,
    ADD COLUMN IF NOT EXISTS "whoop_token_expires_at" bigint,
    ADD COLUMN IF NOT EXISTS "whoop_user_id" bigint;
