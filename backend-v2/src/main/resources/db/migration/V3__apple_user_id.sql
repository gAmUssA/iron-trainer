-- Sign in with Apple (bean 3e6w): the athlete's stable Apple user id (token `sub`),
-- enabling Strava-free accounts. Nullable — only set for athletes who signed in
-- with Apple. A unique index so one Apple id maps to exactly one athlete (partial,
-- so the many NULLs for Strava-only athletes don't collide).
--
-- ⚠ Flyway migrate-at-start is OFF in prod (%dev/%test only) — apply this to
-- Supabase MANUALLY before the image that reads apple_user_id cuts over, or the
-- deploy 500s on the missing column (bean backend-v2-railway-deploy).
ALTER TABLE "public"."athlete"
    ADD COLUMN "apple_user_id" text;

CREATE UNIQUE INDEX "uq_athlete_apple_user_id"
    ON "public"."athlete" ("apple_user_id")
    WHERE "apple_user_id" IS NOT NULL;
