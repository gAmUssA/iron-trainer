---
# iron-trainer-q4xs
title: docs/deploy.md says migrations are manual in prod; they are automatic
status: todo
type: bug
priority: high
created_at: 2026-08-21T14:22:18Z
updated_at: 2026-08-21T14:22:18Z
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
- [ ] Rewrite deploy.md section 2 to say migrations ARE applied automatically at
      startup, and that manual DDL is the thing that breaks it
- [ ] Explain the one real caveat: `%prod` baselines at V2 because V1/V2 were
      applied to Supabase by hand before Flyway was switched on — so those two are
      recorded as done rather than re-run
- [ ] Say what to actually do instead: add the migration, deploy, watch the boot
      log, and verify per the deploy-health note
- [ ] Secondary, non-urgent: `.env.example` still lists Python/FastAPI-era vars
      that nothing reads. Confusing rather than dangerous — clean up in the same pass
