# 0029 — Backend v2: async job envelope for write endpoints (2026-07-18)

Date: 2026-07-18
Status: Accepted
Epic: iron-trainer-eom4 (Phase 7) · Feature: iron-trainer-s6v3 · Pattern: ADR 0020

## Context

FastAPI write endpoints accept `?async=1` and return `{"job": <dict>}`; the
client (`viaJob` in `frontend/src/api.ts`) then polls `GET /api/jobs/{id}` until
the job is terminal. Sync, dedup, plan generate/checkin, import, and nutrition
regenerate all use this — the operations run 10s–minutes (Strava rate limits, the
Anthropic SDK) and a held-open request is fragile on mobile.

backend-v2 had a spike `JobRunner` (virtual-thread, status in the DB) but no
envelope contract: `submit` returned a bare id, there were no `/api/jobs`
endpoints, no dedup, and no startup sweep. The ported write endpoints (dedup,
sync, nutrition regenerate) ran synchronously and ignored `?async`. This is the
prerequisite for flipping any of those endpoints to backend-v2.

## What was ported

`jobs.py` + `repo` job functions + `jobs_router`:

- **`JobRunner.submit(athleteId, kind, Supplier<Object>)` → job dict.** Dedups per
  `(athlete, kind)`: a second submit while one is queued/running returns the
  existing job with `already_running=true` and starts nothing (a process-wide
  lock serializes the check-then-create, mirroring FastAPI's `_submit_lock`).
  The work runs on a virtual thread, outside request scope, and its return value
  is JSON-serialized into `result_json`.
- **`jobDict`** — every `Job` column except `result_json`, plus `result` (the
  parsed `result_json`). Mirrors `repo._job_dict`.
- **`JobResource`** — `GET /api/jobs/{id}` (athlete-scoped; a cross-tenant id
  reads as 404) and `GET /api/jobs/summary` (`{latest, active}`, newest terminal
  and newest active job per kind).
- **Startup sweep** — a `@Observes StartupEvent` marks any queued/running row
  failed ("interrupted by restart"); single-instance workers die with the
  process, so those rows are orphans. Mirrors `fail_stale_jobs`.
- **`?async=1` wired** on nutrition regenerate (`nutrition_regen`), Strava sync
  (`sync`), and Strava dedup (`dedup`).

## Decisions

- **`?async=1` is parsed with `Params.boolOr`, not a JAX-RS `boolean`.** A plain
  `@QueryParam boolean` coerces `"1"` to **false** (JAX-RS only accepts `"true"`),
  but the frontend sends literally `?async=1` and FastAPI/pydantic coerces `1` →
  true. `Params.boolOr(v, false)` returns the default when absent/blank and the
  pydantic-lax bool otherwise (so `1`/`true`/`yes` → true, junk → 422). Other
  boolean params (`full`, `fetch`) keep plain-boolean parsing — the frontend
  sends `true`/`false` for those; full pydantic-lax coercion for them is bean
  4syl's scope.
- **Guard placement matches FastAPI exactly.** Sync async: only the 401 auth gate
  is synchronous — a not-connected athlete gets a *failed job*, not a 409. Dedup
  async: the connection-guard 409 (`validAccessToken`) is acquired
  *synchronously* before the job, so a not-connected athlete still gets a 409; the
  pre-acquired token is passed into the job.
- **The work manages its own transactions.** `runSync`, `runDedup`, and
  `regenerateRaceDay` all take the athlete explicitly and open their own
  `QuarkusTransaction`s — nothing relies on request scope or an ambient tx inside
  the virtual thread.

## Parity notes

- Job dict field set matches `_job_dict` (order-insensitive parity):
  `id, athlete_id, kind, status, created_at, started_at, finished_at, error,
  result`. Timestamps use `PyJson.utcNowIso()` (matches `datetime.now(utc).isoformat()`).
- `kind` values match FastAPI's `KINDS`: `sync`, `dedup`, `nutrition_regen`
  (generate_plan / checkin / import land with their own ports).

## Status of the endpoints

All dormant behind the strangler (`proxy_write_paths` empty). This unblocks the
flip: once these endpoints are added to `proxy_write_paths`, the async envelope
matches what the frontend already sends. Idempotency of proxied writes
(`iron-trainer-0o9b`) is a separate prerequisite for the flip.
