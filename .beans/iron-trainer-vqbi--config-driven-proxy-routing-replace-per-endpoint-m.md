---
# iron-trainer-vqbi
title: Config-driven proxy routing (replace per-endpoint _maybe_proxy)
status: todo
type: task
created_at: 2026-07-15T23:56:54Z
updated_at: 2026-07-15T23:56:54Z
parent: iron-trainer-eom4
---

Today the strangler proxy is hardcoded: only the 4 export endpoints call _maybe_proxy, and it forwards only BEARER traffic. Generalize to a single config-driven path allowlist (env, e.g. PROXY_PATHS or a versioned list of prefixes backend-v2 owns), enforced in one place (ASGI middleware or a shared dependency) rather than sprinkled per-handler. Add each vertical's path once its parity is green. Prereq before flipping readiness (bearer/iOS). Note: zones/pmc are web/cookie surfaces — they won't proxy until Phase 7 session-auth regardless.
