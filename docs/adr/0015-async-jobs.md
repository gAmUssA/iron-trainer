# ADR 0015 — Async background jobs for Strava/Claude operations

**Status:** Accepted · 2026-07-12

## Context

Every operation touching Strava or Claude (sync, archive import, dedup, plan
generation, weekly check-in, race-day nutrition regen) ran synchronously inside
the HTTP request: the user waited on a spinner for 30s+ (LLM) to minutes
(imports), a proxy timeout lost the outcome, and nothing recorded when these
external calls last happened.

## Decision

**In-process, thread-based jobs with DB-persisted status. No broker.**
The app runs as a single uvicorn worker on Railway; Celery/Redis/arq would add
an operational dependency to solve a coordination problem we don't have. The
verified foundation makes threads safe here: the SQLite engine is
`check_same_thread=False` with WAL, `get_session()` is a fresh session per
call, and Postgres pooling is per-connection.

- **`job` table** (migration `e6b8d0f2a4c6`): athlete-scoped rows with
  `kind`, `status` (queued→running→succeeded|failed), ISO timestamps,
  `result_json`, `error`. The row is the source of truth.
- **`app/jobs.py`** — the only place threads live. `submit()` enforces one
  active job per (athlete, kind), returning the existing job with
  `already_running` instead of duplicating rate-limited work. The worker sets
  `auth.set_current_athlete_id()` FIRST — request-scoped ContextVars do not
  cross threads. Results are the exact dicts the sync endpoints returned,
  JSON-encoded into `result_json`, so clients render them unchanged.
- **Restart hygiene**: threads die with the process; `fail_stale_running()`
  in lifespan marks orphaned queued/running rows failed("interrupted by
  restart") — the UI shows a failure, never a forever-spinner.
- **Opt-in `?async=1`** on the six heavy endpoints → 202-style `{job}` reply.
  Defaults stay synchronous: the iOS check-in's contract is untouched
  (follow-up bean for iOS adoption), and tests/scripts keep working.
- **`/api/jobs/{id}`** (athlete-scoped, cross-tenant reads 404) and
  **`/api/jobs/summary`** (latest terminal job per kind + active jobs).
- **Web** always passes `async=1` and polls (1.5s, 10-min cap) via a shared
  `pollJob` helper; the six actions' result-rendering code is unchanged
  because job results reuse the sync shapes. Freshness UI: Setup shows
  "Strava last called X ago" (newest of sync/import/dedup/check-in jobs) and
  a "running in background — safe to leave this page" line; the Plan tab
  shows "Plan generated X ago · Claude/template" from the last generate job.

## Consequences

- A deploy mid-job fails that job visibly; the user re-runs. Acceptable for
  weekly-cadence operations; a persistent queue would be the next step only
  if multi-worker scaling ever happens.
- Job history doubles as an audit log of external API usage (relevant to the
  Strava rate/compliance posture).
- SQLite write contention between worker and requests is bounded by WAL +
  short sessions; at this scale it is a non-issue.

## Verification

`tests/test_jobs.py` (6): async generate → poll to succeeded with the sync
result shape; duplicate submit returns `already_running` and runs the work
once; not-connected sync job fails with the right error; cross-athlete job
reads 404; stale-running sweep; summary shape. Full suite 163 green; ruff;
frontend build. Live browser pass: Generate ran as a background job (~19s of
real Claude work) with `/api/jobs/summary` showing `running` mid-flight, then
"Plan generated just now · Claude" and "Strava last called 1 min ago" rendered.

> **Update (2026-07-15):** the iOS-adoption follow-up shipped — the iOS Weekly Check-in submits `?async=1` and polls the job API with resume-on-appear (PR #22, bean iron-trainer-tcso, TestFlight 202607131537).
