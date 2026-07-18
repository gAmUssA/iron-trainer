---
# iron-trainer-gb30
title: 'backend-v2: strangler forwards POST + cookies; flip first write'
status: in-progress
type: feature
priority: normal
created_at: 2026-07-17T04:45:21Z
updated_at: 2026-07-18T15:05:22Z
parent: iron-trainer-eom4
---

Extend backend/app/strangler.py (GET-only, bearer-only, cookie traffic served locally) to forward allowlisted POST/PUT/DELETE AND the session Cookie header so web writes reach backend-v2 (which now verifies the cookie — slice A). Then flip a first ported write endpoint (fitness-tests writes, bean 93o6) via PROXY_PATHS + a method allowlist. NOTE: slice A's cookie verify is dead on the real proxied path until this lands.

## Progress
Strangler write-forwarding capability BUILT (this PR): separate PROXY_WRITE_PATHS allowlist, bearer-or-cookie eligibility, Cookie+Authorization forwarding, request-body buffering, and asymmetric fallback (unreachable→local replay; 5xx/post-send→no local retry to avoid double-apply). 14 strangler tests (6 new) + 215/215 backend suite green. ADR 0023.

REMAINING: the actual prod flip of POST /api/tests/result (+/apply, /schedule) via Railway PROXY_WRITE_PATHS — a deliberate, separately-confirmed step (first live mutation traffic on Quarkus).

## FLIP EXECUTED (2026-07-18)

Set PROXY_WRITE_PATHS=/api/tests/* on the iron-trainer (FastAPI) service, prod env
(user-approved, option B). FastAPI redeployed SUCCESS (deploy 29e09e0d). Fitness-test
writes — POST /api/tests/result, /result/{id}/apply, /{slug}/schedule — now forward
to backend-v2 (first live mutation traffic on Quarkus).

Pre-flip safety verified: iron-trainer + backend-v2 share the SAME Postgres
(Supabase pooler), SESSION_SECRET matches across both, backend-v2 AUTH_REQUIRED=true,
write endpoints parity-tested (test_tests_record/apply/schedule_parity), request-log
filter live (PR #64). backend-v2 healthy on the logging image, serving read traffic.

Rollback: clear PROXY_WRITE_PATHS → writes revert to FastAPI-local instantly.

REMAINING: observe the first forwarded write land on backend-v2 (RequestLogFilter
INFO: 'POST /api/tests/result athlete=N -> 200 (Nms)') with no 5xx and no
double-apply. Awaiting organic/test write traffic.
