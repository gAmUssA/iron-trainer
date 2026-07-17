---
# iron-trainer-gb30
title: 'backend-v2: strangler forwards POST + cookies; flip first write'
status: todo
type: feature
created_at: 2026-07-17T04:45:21Z
updated_at: 2026-07-17T04:45:21Z
parent: iron-trainer-eom4
---

Extend backend/app/strangler.py (GET-only, bearer-only, cookie traffic served locally) to forward allowlisted POST/PUT/DELETE AND the session Cookie header so web writes reach backend-v2 (which now verifies the cookie — slice A). Then flip a first ported write endpoint (fitness-tests writes, bean 93o6) via PROXY_PATHS + a method allowlist. NOTE: slice A's cookie verify is dead on the real proxied path until this lands.
