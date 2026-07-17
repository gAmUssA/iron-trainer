---
# iron-trainer-39ry
title: 'backend-v2: session-auth cleanup (test crypto dup, config reads)'
status: todo
type: task
priority: normal
created_at: 2026-07-17T04:29:41Z
updated_at: 2026-07-17T04:45:21Z
parent: iron-trainer-eom4
---

Low-severity cleanups from the PR #55 review (deferred, not blocking): (1) SessionAuthTest.signSession re-implements the itsdangerous signing algorithm — a 3rd hand-maintained copy alongside SessionCookie (verify) and the golden fixture; extract a shared test signer or drive the E2E test off the Python golden fixture. (2) BearerAuthFilter does two separate ConfigProvider lookups per request (auth-required + session-secret); could resolve together. Both bounded by the real-Python golden-fixture parity anchor and are micro-costs.
