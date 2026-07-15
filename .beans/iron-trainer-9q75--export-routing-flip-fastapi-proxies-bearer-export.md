---
# iron-trainer-9q75
title: 'Export routing flip: FastAPI proxies bearer export traffic to backend-v2'
status: completed
type: task
priority: normal
created_at: 2026-07-15T21:25:38Z
updated_at: 2026-07-15T22:25:42Z
parent: iron-trainer-u3zo
---

Thin reverse-proxy in export_router: bearer-authenticated export requests forward to EXPORT_PROXY_URL (backend-v2 over Railway private network); session-cookie requests stay Python-served. Env kill-switch = instant rollback.

## Summary of Changes

Shipped in PR #38: bearer export traffic (workout .fit/.zwo/.itw + plan.itw) proxies to backend-v2 over Railway private network when EXPORT_PROXY_URL set; backend-v2 re-authenticates the bearer itself. Session/web + zips stay local until Phase 7. Fallback-to-local on any proxy failure; default-off env = instant rollback. Full RFC 7230 hop-by-hop handling incl. content-encoding drop (httpx decompresses). 4 tests. EXPORT_PROXY_URL set on iron-trainer service post-merge — flip is LIVE.
