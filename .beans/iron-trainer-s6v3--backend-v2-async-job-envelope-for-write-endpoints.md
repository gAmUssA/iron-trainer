---
# iron-trainer-s6v3
title: 'backend-v2: async job envelope for write endpoints (?async=1 → {job})'
status: todo
type: task
priority: low
created_at: 2026-07-17T00:17:36Z
updated_at: 2026-07-18T19:53:10Z
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
