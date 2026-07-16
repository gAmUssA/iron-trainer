---
# iron-trainer-a4u2
title: 'strangler proxy hardening (from PR #47 review)'
status: todo
type: task
priority: normal
created_at: 2026-07-16T03:01:14Z
updated_at: 2026-07-16T03:01:14Z
---

Cleanups on the already-merged strangler proxy (app/strangler.py, config.py) surfaced by code-review; none block current traffic (exports only), but the seam is now shared by every future vertical:

1. EXPORT_PROXY_URL is now the MASTER flip for ALL proxied verticals, but its name implies exports-only. Clearing it to stop export proxying silently disables readiness/etc. Rename to a vertical-neutral var (e.g. PROXY_BASE_URL / BACKEND_V2_URL) with EXPORT_PROXY_URL kept as a back-compat alias.
2. config.proxy_path_list wraps the lru_cached tuple in list(...) → re-allocates every request despite the 'cached' docstring. Return the cached tuple (path_matches only iterates) or cache the list.
3. Prefix match '/api/export/workout/*' forwards unregistered siblings (e.g. /api/export/workout/ with no id) to backend-v2 instead of a local 404/422. Consider tightening patterns or validating.
4. Proxy forwards only the Authorization header, dropping If-None-Match/If-Modified-Since/Range → no 304/206 (full re-download). Low severity for tiny .fit/.itw bodies; revisit if a larger-payload vertical is proxied.

Source: code-review of feature/readiness-tz (strangler.py:76/85, config.py:52/111).
