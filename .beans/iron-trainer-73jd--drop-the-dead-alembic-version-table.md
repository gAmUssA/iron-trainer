---
# iron-trainer-73jd
title: Drop the dead alembic_version table
status: completed
type: task
priority: normal
created_at: 2026-08-08T23:24:17Z
updated_at: 2026-08-09T16:10:03Z
parent: iron-trainer-y2yz
---

V1__baseline.sql:70-72 carries the Alembic version table (verbatim supabase dump). Alembic + FastAPI are gone; Flyway owns migrations (baselined at 2). No Java references it.

## Todo
- [ ] New Flyway migration (next free version — V8+ after PR #109's V7 merges): DROP TABLE IF EXISTS "public"."alembic_version";
- [ ] Confirm no external Supabase job/dashboard reads it first.

## Notes
LOW risk. Keep the SERIAL/nextval sequence DDL from the baseline — Hibernate IDENTITY works against it; rewriting buys nothing.

## Summary of Changes
V8__drop_alembic_version.sql drops the dead Alembic bookkeeping table (Flyway owns migrations; no code refs). ADR 0062.
