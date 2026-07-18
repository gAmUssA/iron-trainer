---
# iron-trainer-v0ma
title: 'backend-v2: per-request logging filter (observability for the write flip)'
status: completed
type: task
priority: normal
created_at: 2026-07-18T14:11:58Z
updated_at: 2026-07-18T14:33:57Z
---

Before flipping live write traffic to backend-v2 (gb30), add a cross-cutting request/response logging filter so mutations are investigable: one line per request with method, path, athlete id, status, and duration_ms — INFO for writes (POST/PUT/DELETE/PATCH), ERROR for 5xx, quiet (DEBUG) for reads/4xx (the access log already covers those). Parity-safe: logging only, no response changes. Blocks gb30.

## Summary of Changes

Added io.gamov.irontrainer.observability.RequestLogFilter (@Provider,
ContainerRequestFilter + ContainerResponseFilter): one line per request —
method, path, athlete, status, duration_ms. Tiered severity: 5xx→ERROR,
writes (POST/PUT/DELETE/PATCH)→INFO, reads/other→DEBUG (access log already
covers those). Logging only — no response mutation (parity-safe).

- CurrentAthlete.idOrNull(): nullable getter for logging (never for tenancy).
- Pure level(status, method) helper, unit-tested (4 tests).

98 backend-v2 + 57 parity tests green (all resource @QuarkusTests exercise the
filter → non-intrusive). Unblocks gb30 (write flip observability).
