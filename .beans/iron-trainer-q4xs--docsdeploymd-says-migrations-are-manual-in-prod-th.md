---
# iron-trainer-q4xs
title: docs/deploy.md says migrations are manual in prod; they are automatic
status: completed
type: bug
priority: high
created_at: 2026-08-21T14:22:18Z
updated_at: 2026-08-25T18:40:22Z
---

`docs/deploy.md` section 2 contradicts the code, in the direction that causes a
failed deploy.

## The contradiction (verified 2026-08-21)

`docs/deploy.md:41-49` says:

> ### 2. ⚠️ Migrations are NOT auto-applied in production
> Flyway `migrate-at-start` is enabled only under `%dev`/`%test`. **In production
> the app does not run migrations at startup**. Any new column/table must be
> applied to Supabase **manually, before** the image that expects it cuts over…

`backend-v2/src/main/resources/application.properties:7`:

```
quarkus.flyway.migrate-at-start=true      # NO profile prefix -> all profiles
```

There is no `%dev.`/`%test.` scoping. Flyway runs on **every** boot, production
included. Two independent corroborations in the same file: lines 12-13 set
`%prod.quarkus.flyway.baseline-on-migrate=true` and `baseline-version=2`, which
would be meaningless if Flyway never ran in prod.

## Why this is a footgun, not a typo

Following the doc actively breaks the deploy. The sequence:

1. Engineer adds a migration, reads the doc, manually runs the DDL against Supabase.
2. Deploys. Flyway starts, finds no history row for that version, tries to apply it.
3. `relation already exists` / `column already exists` -> **migration fails -> boot
   fails -> the deploy dies**, and per [[backend-v2-railway-deploy]] Railway keeps
   serving the old image, so it looks like nothing happened.

The code's behaviour is the RIGHT one (auto-migrate, no manual step). The doc is
simply stale — it predates whatever change removed the profile scoping.

## Corroborating evidence from recent work
- The self-host stack (milestone sgfg) depends entirely on Flyway running at
  container startup, and it demonstrably does — verified booting the prod profile
  against an empty Postgres, which migrated V1->V9.
- The upgrade gate (bean yijb) is built on exactly that behaviour and passes in CI.
- PR #127 adds V10/V11 expecting auto-application.

## Todo
- [x] Rewrite deploy.md section 2 to say migrations ARE applied automatically at
      startup, and that manual DDL is the thing that breaks it
- [x] Explain the one real caveat: `%prod` baselines at V2 because V1/V2 were
      applied to Supabase by hand before Flyway was switched on — so those two are
      recorded as done rather than re-run
- [x] Say what to actually do instead: add the migration, deploy, watch the boot
      log, and verify per the deploy-health note
- [x] Secondary: `.env.example` still lists Python/FastAPI-era vars
      that nothing reads. Confusing rather than dangerous — clean up in the same pass

## Summary of Changes

deploy.md section 2 rewritten in #131. It said migrations were manual in prod; they are
automatic — `quarkus.flyway.migrate-at-start=true` carries no profile prefix. Following
the old text actively broke a deploy: manual DDL succeeds, Flyway then finds no history
row, fails with *relation already exists*, boot fails, and Railway keeps serving the OLD
image — green CI, stale code.

Copilot then caught that the verification step I wrote was itself unsound: it said
absence of the `Migrating schema` line means "already applied", which equally means
Flyway never started or logs were unavailable. Now requires positive evidence — the
migration line, or `Schema is up to date` plus a matching `Current version`.

`.env.example` rewritten today (2026-08-25). It still described the FastAPI app: port
8000, `DATA_DIR`/SQLite, a SQLAlchemy `DATABASE_URL`. Replaced with the vars backend-v2
actually reads (verified against application.properties), the WHOOP block that never
existed, and a note that dev/test need no DB config at all because Dev Services provides
one.
