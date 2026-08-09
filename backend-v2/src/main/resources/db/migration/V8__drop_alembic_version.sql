-- De-Python (bean 73jd): drop the dead Alembic bookkeeping table. Alembic managed
-- the schema under the decommissioned FastAPI backend; Flyway owns migrations now
-- (baselined at 2), and no code references alembic_version. It's a stale 1-row table.
DROP TABLE IF EXISTS "public"."alembic_version";
