---
# iron-trainer-gb30
title: 'backend-v2: strangler forwards POST + cookies; flip first write'
status: in-progress
type: feature
priority: normal
created_at: 2026-07-17T04:45:21Z
updated_at: 2026-07-17T05:15:38Z
parent: iron-trainer-eom4
---

Extend backend/app/strangler.py (GET-only, bearer-only, cookie traffic served locally) to forward allowlisted POST/PUT/DELETE AND the session Cookie header so web writes reach backend-v2 (which now verifies the cookie — slice A). Then flip a first ported write endpoint (fitness-tests writes, bean 93o6) via PROXY_PATHS + a method allowlist. NOTE: slice A's cookie verify is dead on the real proxied path until this lands.

## Progress
Strangler write-forwarding capability BUILT (this PR): separate PROXY_WRITE_PATHS allowlist, bearer-or-cookie eligibility, Cookie+Authorization forwarding, request-body buffering, and asymmetric fallback (unreachable→local replay; 5xx/post-send→no local retry to avoid double-apply). 14 strangler tests (6 new) + 215/215 backend suite green. ADR 0023.

REMAINING: the actual prod flip of POST /api/tests/result (+/apply, /schedule) via Railway PROXY_WRITE_PATHS — a deliberate, separately-confirmed step (first live mutation traffic on Quarkus).
