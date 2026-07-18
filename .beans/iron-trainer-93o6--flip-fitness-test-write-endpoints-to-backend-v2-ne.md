---
# iron-trainer-93o6
title: Flip fitness-test write endpoints to backend-v2 (needs proxy POST + Phase 7)
status: completed
type: task
priority: normal
created_at: 2026-07-16T16:29:40Z
updated_at: 2026-07-18T21:26:58Z
---

The 3 fitness-test mutations (POST /api/tests/result, POST /result/{id}/apply, POST /{slug}/schedule) will be PORTED + parity-verified in backend-v2, but cannot take live traffic yet: the strangler proxy forwards GET-only, and writes need the Phase 7 session-auth write seam. Once the proxy supports forwarding allowlisted POSTs and Phase 7 lands, flip these. Until then the Quarkus impls are dormant/parity-tested. Related: [[iron-trainer-eom4]] (Phase 7 auth), proxy is GET-only by design.

## Update (2026-07-18): stated blockers are RESOLVED

This bean's original body is stale. The two blockers it lists are done:
- Strangler write-forwarding: shipped (proxy_write_paths / PROXY_WRITE_PATHS, ADR 0023) — the proxy is no longer GET-only.
- Session-auth write seam: shipped (backend-v2 verifies the session cookie, ADR 0022).

Plus the write-safety groundwork is now complete: async job envelope (s6v3/ADR 0029) and server-side idempotency (0o9b/ADR 0030).

The 3 fitness-test writes ARE ported (FitnessTestsResource: POST /result, /{slug}/schedule, /result/{id}/apply) and parity-tested. Remaining work is just the FLIP itself: add these paths to prod proxy_write_paths and verify live. This is now unblocked — a deliberate live-write-flip step (see the gb30 latency lesson: watch apply latency after flipping).

## Completed (2026-07-18): flipped live

/api/tests/* writes are in prod PROXY_WRITE_PATHS (fitness-test result/schedule/apply now served by backend-v2). Part of the broader parity-verified flip on 2026-07-18 — see [[backend-v2-railway-deploy]] memory for the full flipped set + rollback lever.
