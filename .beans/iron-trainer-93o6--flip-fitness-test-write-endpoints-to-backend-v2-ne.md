---
# iron-trainer-93o6
title: Flip fitness-test write endpoints to backend-v2 (needs proxy POST + Phase 7)
status: todo
type: task
priority: normal
created_at: 2026-07-16T16:29:40Z
updated_at: 2026-07-16T16:29:40Z
---

The 3 fitness-test mutations (POST /api/tests/result, POST /result/{id}/apply, POST /{slug}/schedule) will be PORTED + parity-verified in backend-v2, but cannot take live traffic yet: the strangler proxy forwards GET-only, and writes need the Phase 7 session-auth write seam. Once the proxy supports forwarding allowlisted POSTs and Phase 7 lands, flip these. Until then the Quarkus impls are dormant/parity-tested. Related: [[iron-trainer-eom4]] (Phase 7 auth), proxy is GET-only by design.
