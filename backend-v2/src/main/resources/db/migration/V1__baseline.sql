-- V1 baseline: pg_dump --schema-only of the LIVE Supabase prod DB
-- (project ejzurxmgxooshocnqcux, Postgres 17), 2026-07-15, cleaned of
-- Supabase/ownership boilerplate. On the shared prod DB Flyway must run
-- with baseline-on-migrate=true baseline-version=1 (never execute this
-- file there); Dev Services / fresh DBs execute it to create the schema.

CREATE OR REPLACE FUNCTION "public"."rls_auto_enable"() RETURNS "event_trigger"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'pg_catalog'
    AS $$
DECLARE
  cmd record;

BEGIN
  FOR cmd IN
    SELECT *
    FROM pg_event_trigger_ddl_commands()
    WHERE command_tag IN ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
      AND object_type IN ('table','partitioned table')
  LOOP
     IF cmd.schema_name IS NOT NULL AND cmd.schema_name IN ('public') AND cmd.schema_name NOT IN ('pg_catalog','information_schema') AND cmd.schema_name NOT LIKE 'pg_toast%' AND cmd.schema_name NOT LIKE 'pg_temp%' THEN
      BEGIN
        EXECUTE format('alter table if exists %s enable row level security', cmd.object_identity);

RAISE LOG 'rls_auto_enable: enabled RLS on %', cmd.object_identity;

EXCEPTION
        WHEN OTHERS THEN
          RAISE LOG 'rls_auto_enable: failed to enable RLS on %', cmd.object_identity;

END;

ELSE
        RAISE LOG 'rls_auto_enable: skip % (either system schema or not in enforced list: %.)', cmd.object_identity, cmd.schema_name;

END IF;

END LOOP;

END;

$$;

CREATE TABLE IF NOT EXISTS "public"."activities" (
    "id" bigint NOT NULL,
    "sport" character varying NOT NULL,
    "start_date" character varying NOT NULL,
    "name" character varying,
    "moving_time" integer,
    "elapsed_time" integer,
    "distance" double precision,
    "avg_power" double precision,
    "weighted_power" double precision,
    "avg_hr" double precision,
    "max_hr" double precision,
    "avg_speed" double precision,
    "elevation_gain" double precision,
    "has_power_meter" integer,
    "tss" double precision,
    "intensity_factor" double precision,
    "tss_method" character varying,
    "device_name" character varying,
    "is_duplicate" integer,
    "primary_id" bigint,
    "raw_json" character varying,
    "created_at" character varying,
    "athlete_id" integer NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."alembic_version" (
    "version_num" character varying(32) NOT NULL
);

CREATE TABLE IF NOT EXISTS "public"."athlete" (
    "id" integer NOT NULL,
    "strava_athlete_id" bigint,
    "name" character varying,
    "strava_access_token" character varying,
    "strava_refresh_token" character varying,
    "strava_token_expires_at" bigint,
    "ftp" double precision,
    "threshold_hr" integer,
    "max_hr" integer,
    "threshold_pace_run" double precision,
    "css_swim" double precision,
    "weekly_hours_target" double precision,
    "updated_at" character varying,
    "race_id" integer,
    "race_name" character varying,
    "race_date" character varying,
    "race_distance" character varying,
    "cutoff_swim_s" integer,
    "cutoff_bike_s" integer,
    "cutoff_finish_s" integer,
    "body_weight_kg" double precision,
    "gel_carb_g" double precision,
    "sweat_rate_l_h" double precision,
    "gi_tolerance" character varying
);

CREATE SEQUENCE IF NOT EXISTS "public"."athlete_id_seq"
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE "public"."athlete_id_seq" OWNED BY "public"."athlete"."id";

CREATE TABLE IF NOT EXISTS "public"."checkin" (
    "id" integer NOT NULL,
    "athlete_id" integer NOT NULL,
    "date" character varying NOT NULL,
    "created_at" character varying,
    "inputs_json" character varying,
    "story_json" character varying,
    "readiness_json" character varying
);

CREATE SEQUENCE IF NOT EXISTS "public"."checkin_id_seq"
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE "public"."checkin_id_seq" OWNED BY "public"."checkin"."id";

CREATE TABLE IF NOT EXISTS "public"."daily_recovery" (
    "id" integer NOT NULL,
    "athlete_id" integer NOT NULL,
    "date" character varying NOT NULL,
    "updated_at" character varying,
    "sleep_h" double precision,
    "deep_h" double precision,
    "rem_h" double precision,
    "awake_h" double precision,
    "sleep_start" character varying,
    "sleep_end" character varying,
    "hrv_ms" double precision,
    "rhr_bpm" double precision,
    "weight_kg" double precision,
    "vo2max" double precision,
    "respiratory_rate" double precision,
    "wrist_temp_c" double precision
);

CREATE SEQUENCE IF NOT EXISTS "public"."daily_recovery_id_seq"
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE "public"."daily_recovery_id_seq" OWNED BY "public"."daily_recovery"."id";

CREATE TABLE IF NOT EXISTS "public"."device_token" (
    "id" integer NOT NULL,
    "athlete_id" integer NOT NULL,
    "name" character varying,
    "pairing_code" character varying,
    "pairing_expires_at" bigint,
    "token_hash" character varying,
    "created_at" character varying,
    "last_used_at" character varying
);

CREATE SEQUENCE IF NOT EXISTS "public"."device_token_id_seq"
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE "public"."device_token_id_seq" OWNED BY "public"."device_token"."id";

CREATE TABLE IF NOT EXISTS "public"."fitness_test_result" (
    "id" integer NOT NULL,
    "athlete_id" integer NOT NULL,
    "test_slug" character varying NOT NULL,
    "sport" character varying NOT NULL,
    "date" character varying NOT NULL,
    "inputs_json" character varying,
    "result_json" character varying,
    "applied" boolean DEFAULT false NOT NULL,
    "created_at" character varying
);

CREATE SEQUENCE IF NOT EXISTS "public"."fitness_test_result_id_seq"
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE "public"."fitness_test_result_id_seq" OWNED BY "public"."fitness_test_result"."id";

CREATE TABLE IF NOT EXISTS "public"."job" (
    "id" integer NOT NULL,
    "athlete_id" integer NOT NULL,
    "kind" character varying NOT NULL,
    "status" character varying NOT NULL,
    "created_at" character varying,
    "started_at" character varying,
    "finished_at" character varying,
    "result_json" character varying,
    "error" character varying
);

CREATE SEQUENCE IF NOT EXISTS "public"."job_id_seq"
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE "public"."job_id_seq" OWNED BY "public"."job"."id";

CREATE TABLE IF NOT EXISTS "public"."metrics_daily" (
    "athlete_id" integer NOT NULL,
    "date" character varying NOT NULL,
    "tss" double precision,
    "ctl" double precision,
    "atl" double precision,
    "tsb" double precision
);

CREATE TABLE IF NOT EXISTS "public"."plan" (
    "id" integer NOT NULL,
    "race_name" character varying,
    "race_date" character varying,
    "status" character varying,
    "summary" character varying,
    "weeks_json" character varying,
    "created_at" character varying,
    "athlete_id" integer NOT NULL,
    "base_weekly_hours" double precision
);

CREATE SEQUENCE IF NOT EXISTS "public"."plan_id_seq"
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE "public"."plan_id_seq" OWNED BY "public"."plan"."id";

CREATE TABLE IF NOT EXISTS "public"."planned_workouts" (
    "id" integer NOT NULL,
    "plan_id" integer,
    "date" character varying NOT NULL,
    "sport" character varying NOT NULL,
    "title" character varying,
    "description" character varying,
    "structure_json" character varying,
    "duration_s" integer,
    "distance_m" double precision,
    "planned_tss" double precision,
    "intensity" character varying,
    "fit_path" character varying,
    "zwo_path" character varying,
    "status" character varying,
    "matched_activity_id" bigint,
    "created_at" character varying,
    "athlete_id" integer NOT NULL
);

CREATE SEQUENCE IF NOT EXISTS "public"."planned_workouts_id_seq"
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE "public"."planned_workouts_id_seq" OWNED BY "public"."planned_workouts"."id";

CREATE TABLE IF NOT EXISTS "public"."race" (
    "id" integer NOT NULL,
    "slug" character varying NOT NULL,
    "name" character varying NOT NULL,
    "date" character varying NOT NULL,
    "distance" character varying NOT NULL,
    "city" character varying,
    "country" character varying,
    "cutoff_swim_s" integer,
    "cutoff_bike_s" integer,
    "cutoff_finish_s" integer
);

CREATE SEQUENCE IF NOT EXISTS "public"."race_id_seq"
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE "public"."race_id_seq" OWNED BY "public"."race"."id";

ALTER TABLE ONLY "public"."athlete" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."athlete_id_seq"'::"regclass");

ALTER TABLE ONLY "public"."checkin" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."checkin_id_seq"'::"regclass");

ALTER TABLE ONLY "public"."daily_recovery" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."daily_recovery_id_seq"'::"regclass");

ALTER TABLE ONLY "public"."device_token" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."device_token_id_seq"'::"regclass");

ALTER TABLE ONLY "public"."fitness_test_result" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."fitness_test_result_id_seq"'::"regclass");

ALTER TABLE ONLY "public"."job" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."job_id_seq"'::"regclass");

ALTER TABLE ONLY "public"."plan" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."plan_id_seq"'::"regclass");

ALTER TABLE ONLY "public"."planned_workouts" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."planned_workouts_id_seq"'::"regclass");

ALTER TABLE ONLY "public"."race" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."race_id_seq"'::"regclass");

ALTER TABLE ONLY "public"."activities"
    ADD CONSTRAINT "activities_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."alembic_version"
    ADD CONSTRAINT "alembic_version_pkc" PRIMARY KEY ("version_num");

ALTER TABLE ONLY "public"."athlete"
    ADD CONSTRAINT "athlete_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."checkin"
    ADD CONSTRAINT "checkin_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."daily_recovery"
    ADD CONSTRAINT "daily_recovery_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."device_token"
    ADD CONSTRAINT "device_token_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."fitness_test_result"
    ADD CONSTRAINT "fitness_test_result_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."job"
    ADD CONSTRAINT "job_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."metrics_daily"
    ADD CONSTRAINT "metrics_daily_pkey" PRIMARY KEY ("athlete_id", "date");

ALTER TABLE ONLY "public"."plan"
    ADD CONSTRAINT "plan_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."planned_workouts"
    ADD CONSTRAINT "planned_workouts_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."race"
    ADD CONSTRAINT "race_pkey" PRIMARY KEY ("id");

CREATE INDEX "ix_activities_athlete_id" ON "public"."activities" USING "btree" ("athlete_id");

CREATE INDEX "ix_activities_sport" ON "public"."activities" USING "btree" ("sport");

CREATE INDEX "ix_activities_start_date" ON "public"."activities" USING "btree" ("start_date");

CREATE UNIQUE INDEX "ix_athlete_strava_athlete_id" ON "public"."athlete" USING "btree" ("strava_athlete_id");

CREATE INDEX "ix_checkin_athlete_id" ON "public"."checkin" USING "btree" ("athlete_id");

CREATE INDEX "ix_daily_recovery_athlete_id" ON "public"."daily_recovery" USING "btree" ("athlete_id");

CREATE INDEX "ix_daily_recovery_date" ON "public"."daily_recovery" USING "btree" ("date");

CREATE INDEX "ix_device_token_athlete_id" ON "public"."device_token" USING "btree" ("athlete_id");

CREATE INDEX "ix_device_token_pairing_code" ON "public"."device_token" USING "btree" ("pairing_code");

CREATE UNIQUE INDEX "ix_device_token_token_hash" ON "public"."device_token" USING "btree" ("token_hash");

CREATE INDEX "ix_fitness_test_result_athlete_id" ON "public"."fitness_test_result" USING "btree" ("athlete_id");

CREATE INDEX "ix_fitness_test_result_test_slug" ON "public"."fitness_test_result" USING "btree" ("test_slug");

CREATE INDEX "ix_job_athlete_id" ON "public"."job" USING "btree" ("athlete_id");

CREATE INDEX "ix_job_athlete_kind_status" ON "public"."job" USING "btree" ("athlete_id", "kind", "status");

CREATE INDEX "ix_plan_athlete_id" ON "public"."plan" USING "btree" ("athlete_id");

CREATE INDEX "ix_planned_workouts_athlete_id" ON "public"."planned_workouts" USING "btree" ("athlete_id");

CREATE INDEX "ix_planned_workouts_date" ON "public"."planned_workouts" USING "btree" ("date");

CREATE INDEX "ix_planned_workouts_plan_id" ON "public"."planned_workouts" USING "btree" ("plan_id");

CREATE UNIQUE INDEX "ix_race_slug" ON "public"."race" USING "btree" ("slug");

CREATE UNIQUE INDEX "uq_daily_recovery_athlete_date" ON "public"."daily_recovery" USING "btree" ("athlete_id", "date");

ALTER TABLE ONLY "public"."checkin"
    ADD CONSTRAINT "checkin_athlete_id_fkey" FOREIGN KEY ("athlete_id") REFERENCES "public"."athlete"("id") ON DELETE CASCADE;

ALTER TABLE ONLY "public"."daily_recovery"
    ADD CONSTRAINT "daily_recovery_athlete_id_fkey" FOREIGN KEY ("athlete_id") REFERENCES "public"."athlete"("id") ON DELETE CASCADE;

ALTER TABLE ONLY "public"."activities"
    ADD CONSTRAINT "fk_activities_athlete_id_athlete" FOREIGN KEY ("athlete_id") REFERENCES "public"."athlete"("id") ON DELETE CASCADE;

ALTER TABLE ONLY "public"."athlete"
    ADD CONSTRAINT "fk_athlete_race_id_race" FOREIGN KEY ("race_id") REFERENCES "public"."race"("id") ON DELETE SET NULL;

ALTER TABLE ONLY "public"."device_token"
    ADD CONSTRAINT "fk_device_token_athlete_id_athlete" FOREIGN KEY ("athlete_id") REFERENCES "public"."athlete"("id") ON DELETE CASCADE;

ALTER TABLE ONLY "public"."fitness_test_result"
    ADD CONSTRAINT "fk_fitness_test_result_athlete_id_athlete" FOREIGN KEY ("athlete_id") REFERENCES "public"."athlete"("id") ON DELETE CASCADE;

ALTER TABLE ONLY "public"."plan"
    ADD CONSTRAINT "fk_plan_athlete_id_athlete" FOREIGN KEY ("athlete_id") REFERENCES "public"."athlete"("id") ON DELETE CASCADE;

ALTER TABLE ONLY "public"."planned_workouts"
    ADD CONSTRAINT "fk_planned_workouts_athlete_id_athlete" FOREIGN KEY ("athlete_id") REFERENCES "public"."athlete"("id") ON DELETE CASCADE;

ALTER TABLE ONLY "public"."job"
    ADD CONSTRAINT "job_athlete_id_fkey" FOREIGN KEY ("athlete_id") REFERENCES "public"."athlete"("id") ON DELETE CASCADE;

ALTER TABLE ONLY "public"."metrics_daily"
    ADD CONSTRAINT "metrics_daily_athlete_id_fkey" FOREIGN KEY ("athlete_id") REFERENCES "public"."athlete"("id") ON DELETE CASCADE;

ALTER TABLE ONLY "public"."planned_workouts"
    ADD CONSTRAINT "planned_workouts_plan_id_fkey" FOREIGN KEY ("plan_id") REFERENCES "public"."plan"("id") ON DELETE CASCADE;
