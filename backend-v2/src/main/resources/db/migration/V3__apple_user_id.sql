-- Sign in with Apple (bean 3e6w): the athlete's stable Apple user id (token `sub`),
-- enabling Strava-free accounts. Nullable — only set for athletes who signed in
-- with Apple. A unique index so one Apple id maps to exactly one athlete (partial,
-- so the many NULLs for Strava-only athletes don't collide).
--
-- Flyway migrate-at-start is now ON in prod (baseline at V2), so this applies
-- automatically on deploy — no manual Supabase ALTER needed.
ALTER TABLE "public"."athlete"
    ADD COLUMN "apple_user_id" text;

CREATE UNIQUE INDEX "uq_athlete_apple_user_id"
    ON "public"."athlete" ("apple_user_id")
    WHERE "apple_user_id" IS NOT NULL;
