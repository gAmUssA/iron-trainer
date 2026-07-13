---
# iron-trainer-pnq0
title: Async job system for Strava/Claude operations
status: completed
type: feature
priority: high
created_at: 2026-07-12T16:24:03Z
updated_at: 2026-07-13T03:40:11Z
---

Viktor: operations hitting Strava or Claude must not block the UI; status tracked in DB; frontend shows when the API was last called / plan last generated.
- [x] job table (e6b8d0f2a4c6) + athlete-scoped CRUD; lifespan sweep fails orphans
- [x] jobs.py: daemon threads, explicit ContextVar set, one active per (athlete, kind) with already_running
- [x] ?async=1 on all six; GET /api/jobs/{id} (404 cross-tenant) + /summary (latest terminal + active)
- [x] Web: pollJob for all six (result shapes reused); Setup last-called + background-running lines; Plan generated-when/how — all verified live
- [x] iOS checkin untouched (sync default preserved)
- [x] 8 job tests, ADR 0015, PR #21 merged after low+medium self-review (8 fixes) + 2 Copilot passes

## Summary of Changes

Thread-based in-process job system (no broker): job table + jobs.py runner (submit lock, ContextVar-derived tenancy, restart sweep), ?async=1 on all six Strava/Claude endpoints, /api/jobs/{id} + /summary (2 queries), web polls with transient tolerance + reload resume-watch, freshness UI (Strava last called / plan generated when·how). Hardened through low+medium self-reviews (8 findings fixed) and two Copilot passes. iOS sync contract untouched; follow-up candidate: iOS job adoption.
