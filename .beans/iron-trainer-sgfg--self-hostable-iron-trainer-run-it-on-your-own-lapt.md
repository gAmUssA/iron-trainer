---
# iron-trainer-sgfg
title: Self-hostable Iron Trainer — run it on your own laptop
status: todo
type: milestone
priority: high
created_at: 2026-08-18T16:09:15Z
updated_at: 2026-08-18T16:09:15Z
---

Today Iron Trainer is a SaaS deployment only. Make it something an athlete can run
on their own laptop with Docker, without being a developer and without rebuilding
anything.

## Why this is not just "write a docker-compose.yml"

Verified against the current tree (2026-08-18):

1. **No image is published anywhere.** `.github/workflows` has `ci`, `backend-v2`
   and `ios` — none of them push to a registry. The only way to run the app today
   is `backend-v2/Dockerfile`, which builds a **GraalVM native image** with
   `-Dquarkus.native.native-image-xmx=5g` and takes ~10 minutes. A non-technical
   user must never do that. **Publishing prebuilt multi-arch images is the
   prerequisite for the whole milestone** — everything else is downstream of it.

2. **Strava OAuth cannot be shipped.** `strava.client-id` / `client-secret` are
   per-deployment. A self-hoster must register their OWN Strava API application
   and paste two values in. This is the single biggest onboarding cliff for a
   non-technical athlete, and it is what makes bulk archive import load-bearing
   rather than a nice-to-have.

3. **Anthropic is optional and already degrades correctly.** `ANTHROPIC_API_KEY`
   defaults to the `no-key` sentinel and `NutritionLlm.available()` falls back to
   the deterministic planner, so the app boots and works with no AI key.

## Verified good news (tested, not assumed)

- **Fresh-DB migration works.** The `%prod` profile sets
  `flyway.baseline-on-migrate=true, baseline-version=2` for Supabase reasons, which
  looked like it would skip V1/V2 on a brand-new database. Booted the prod profile
  against an empty Postgres 17 container: Flyway logs *"All configured schemas are
  empty; baseline operation skipped"* and runs V1->V9. 16 tables, history at V9.
  No change needed.
- **No-login single-user mode already exists.** `AUTH_REQUIRED` defaults false and
  `DEFAULT_ATHLETE_ID` defaults 1; `/api/status` on a clean boot returns
  `auth_required:false, authenticated:true`. Local mode is a hardening + UX job,
  not a from-scratch build.
- **Bulk import already exists for two of three sources.** `StravaArchive.java`
  (POST /api/strava/import) and `WhoopArchive.java` both parse export ZIPs and are
  already wired into the web UI. Apple Health has NO archive parser — that is the
  one genuinely new ingest.

## Known defect to fix on the way

`strava.redirect-uri` defaults to `http://localhost:8000/api/strava/callback` — a
leftover from the decommissioned FastAPI service. The app serves on 8080, so the
default is wrong for exactly the local single-container case this milestone is about.

## Shape

Seven epics. E1 gates everything; E3+E4 are what make a laptop install useful
without OAuth; E7 is deliberately separate from the Boot UI dev-console work,
which is NOT part of this milestone (see that bean for why).
