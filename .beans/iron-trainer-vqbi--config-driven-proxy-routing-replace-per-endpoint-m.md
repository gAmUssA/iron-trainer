---
# iron-trainer-vqbi
title: Config-driven proxy routing (replace per-endpoint _maybe_proxy)
status: scrapped
type: task
priority: normal
created_at: 2026-07-15T23:56:54Z
updated_at: 2026-07-21T06:13:06Z
parent: iron-trainer-eom4
---

Today the strangler proxy is hardcoded: only the 4 export endpoints call _maybe_proxy, and it forwards only BEARER traffic. Generalize to a single config-driven path allowlist (env, e.g. PROXY_PATHS or a versioned list of prefixes backend-v2 owns), enforced in one place (ASGI middleware or a shared dependency) rather than sprinkled per-handler. Add each vertical's path once its parity is green. Prereq before flipping readiness (bearer/iOS). Note: zones/pmc are web/cookie surfaces — they won't proxy until Phase 7 session-auth regardless.

## Summary of Changes

Generalized the per-handler export proxy into one config-driven seam:

- **app/strangler.py** (new) — `StranglerProxyMiddleware` + `path_matches()` + a `fetch()` network seam. Proxies a request only when: GET + EXPORT_PROXY_URL set + path in the allowlist + bearer auth. Everything else (non-GET, cookie/session, unmatched path, backend-v2 unreachable) serves locally. Async httpx; hop-by-hop headers stripped.
- **config.py** — new `proxy_paths` (env PROXY_PATHS) + `proxy_path_list` property. Patterns support exact + `*` prefix. Default = `/api/export/workout/*,/api/export/plan.itw` (exactly today's proxied set → behaviour-neutral). EXPORT_PROXY_URL kept as the backend-v2 base URL for env back-compat.
- **main.py** — middleware wired between Session and CORS (proxied requests skip Session/Auth; responses still get CORS).
- **export_router.py** — removed `_maybe_proxy` + per-handler calls + dead imports; handlers are now local-only.
- Tests: rewrote test_export_proxy.py against the new seam; added test_strangler.py (matcher, configured-vertical flip, unlisted-path, non-GET guard, query passthrough).

Flipping a vertical is now a one-line PROXY_PATHS config change. Readiness (parity-green) can be flipped by appending /api/metrics/readiness/today — NOT done here (kept behaviour-neutral); it's the explicit next step.

204 unit tests + 19 parity tests green.

## Code-review fixes (local, high effort)

Addressed all in-scope findings:
- **5xx fallback**: a backend-v2 5xx now falls back to local serving (4xx pass through as legitimate parity-matched outcomes) — makes the 'never break a client' guarantee real.
- **Multi-value headers**: proxied responses preserve repeated headers (e.g. Set-Cookie) via multi_items() + MutableHeaders.append (was a dict-comprehension that collapsed them).
- **Pure ASGI middleware**: rewrote from BaseHTTPMiddleware → pure ASGI so pass-through traffic (SPA/StaticFiles, StreamingResponse) is never buffered.
- **Shared httpx client** (keep-alive pool, lazy, closed in lifespan) instead of per-request AsyncClient.
- **Cached PROXY_PATHS parse** (lru_cache) on the hot path.
- **Shared test fixtures** (proxy_stub / block_proxy in conftest) — de-duped stub scaffolding.
- Path-param 422-bypass: accepted (inherent to proxy-before-routing; the 5xx fallback covers the dangerous v2-500 case).

Out-of-scope readiness findings filed as follow-ups: [[iron-trainer-umwz]] (TZ, blocks the readiness flip), [[iron-trainer-t4md]] (f0signed -0).

206 unit + 19 parity tests green.

## Reasons for Scrapping 2026-07-21
Obsolete: this targeted the FastAPI strangler proxy (config-driven routing to replace per-endpoint _maybe_proxy). The strangler proxy is gone — backend-v2 is now the direct front door and FastAPI is decommissioned, so there is no proxy layer left to make config-driven.
