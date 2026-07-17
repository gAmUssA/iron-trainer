---
# iron-trainer-qdr9
title: 'backend-v2: verify itsdangerous session cookie'
status: completed
type: feature
created_at: 2026-07-17T04:45:21Z
updated_at: 2026-07-17T04:45:21Z
parent: iron-trainer-eom4
---

Keystone of Phase 7 — MERGED in PR #55. backend-v2 verifies the Starlette 'session' cookie byte-exact (itsdangerous 2.x TimestampSigner, HMAC-SHA1, django-concat key), so web (cookie-only) clients resolve to an athlete. Verify-only — minting stays FastAPI-side.

## Summary of Changes
SessionCookie verifier + BearerAuthFilter wiring (bearer wins → cookie → configurable default athlete) + boot guard (fail-fast when auth-required and no SESSION_SECRET, FastAPI parity) + last-wins cookie parse. 11 tests incl. real-Python itsdangerous 2.2.0 golden fixture + E2E bearer-wins; 85/85 + 35 parity green. High-effort review drove 5 hardening fixes pre-merge. ADR 0022.
