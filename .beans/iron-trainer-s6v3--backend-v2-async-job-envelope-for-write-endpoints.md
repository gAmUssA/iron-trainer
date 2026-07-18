---
# iron-trainer-s6v3
title: 'backend-v2: async job envelope for write endpoints (?async=1 → {job})'
status: todo
type: task
priority: low
created_at: 2026-07-17T00:17:36Z
updated_at: 2026-07-18T20:08:09Z
---

FastAPI write endpoints accept ?async=1 (alias run_async) and return {"job": id}; the client polls GET /api/jobs/{id}. backend-v2 ports (dedup, and earlier record/regen paths) run synchronously and ignore the async param — a divergent contract for async callers. v2 has a JobRunner; wire the async envelope for these write endpoints once they take traffic (Phase 7 / write flip [[iron-trainer-93o6]]). Dormant until then (POST endpoints aren't proxied). Source: code-review of feature/strava-dedup (StravaResource async path).

## Summary of Changes

Ported the async job envelope to backend-v2 (ADR 0029) + backfilled the nutrition-LLM ADR (0028, fd31).

- **JobRunner**: submit() now returns the job dict (id/kind/status/…/result) and dedups per (athlete,kind) → already_running=true (process-wide lock, mirrors _submit_lock); virtual-thread work serialized to result_json; startup sweep marks orphaned queued/running rows failed.
- **JobResource** (NEW): GET /api/jobs/{id} (athlete-scoped, 404 cross-tenant) + GET /api/jobs/summary ({latest, active} per kind).
- **?async=1 wired** on nutrition regenerate (nutrition_regen), Strava sync (sync), Strava dedup (dedup). Guard placement matches FastAPI: sync 409 surfaces as a failed job; dedup 409 stays synchronous.
- **Params.boolOr** (NEW): fixes the JAX-RS boolean trap — ?async=1 with a plain boolean coerces to false; boolOr parses pydantic-lax (1/true/yes → true, junk → 422, absent → default). This was caught by the async envelope test.
- Tests: JobRunner dedup + dict, JobResource end-to-end async poll + 404 + summary. Full suite 119 green.

Endpoints stay dormant (proxy_write_paths empty). Unblocks flipping these endpoints once idempotency (0o9b) lands.

## Code-review + CI fixes (PR #69)

CI caught a native boot crash; the high-effort review added 6 findings. Triaged:
- FIXED (CI native): startup sweep queried the job table at boot → crashed on the empty smoke-run DB (Flyway off in prod profile). Sweep is now best-effort (try/catch) — hygiene must never block boot.
- FIXED #1/#2 (CONFIRMED): strava sync ?full=1 and dedup ?fetch=1 were coerced to false by plain JAX-RS boolean binding — now parsed via Params.boolOr (pydantic-lax parity). Pre-existing bugs surfaced by the async fix.
- FIXED #3 (CONFIRMED): result_json was written with the plain ObjectMapper (compact) — now PyJson.dumps for shared-DB byte parity with FastAPI json.dumps.
- FIXED #6 (PLAUSIBLE): submit() re-read the job AFTER starting the worker, so the envelope status raced the worker — now the queued-state dict is captured inside the create tx before the thread starts (matches FastAPI create_job).
- KEPT #4: summary 'latest' 200-row cap is FAITHFUL to FastAPI repo.latest_jobs_by_kind (.limit(200)); fixing would diverge. Documented.
- NOTED #5 (PLAUSIBLE, low): parseResult returns null + logs on an unparseable result_json (e.g. cross-backend NaN tokens Jackson rejects); job results are ints/strings so no NaN in practice. Left best-effort.
