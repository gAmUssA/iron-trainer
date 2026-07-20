---
# iron-trainer-7fso
title: 'Phase 7 front-door cutover: serve the SPA from backend-v2 (Quarkus) + domain swap, decommission FastAPI'
status: in-progress
type: feature
priority: critical
created_at: 2026-07-20T18:32:37Z
updated_at: 2026-07-20T19:06:33Z
---

Make backend-v2 the true front door: bundle/serve the React SPA (with client-side routing fallback) from Quarkus so it serves the whole app (SPA + all API + login), then swap the public domain from the FastAPI service to backend-v2, verify end-to-end, and decommission FastAPI. Child of foi1.

## Progress 2026-07-20: SPA serving implemented + verified locally
- `SpaFallback` (JAX-RS `ExceptionMapper<NotFoundException>`) serves index.html for BrowserRouter deep links; passes through 404 for `api/*`, `q/*`, and dotted asset paths. Native-safe (getResourceAsStream + `META-INF/resources/**` native include).
- Built SPA committed to `backend-v2/src/main/resources/META-INF/resources/` (Railway build context is locked to backend-v2/, so frontend/ can't be built in-Docker; `scripts/build-frontend.sh` rebuilds+syncs). Proper Docker-build integration deferred to [[iron-trainer-ghbo]].
- Local dev-mode verification (port 8099): / and deep links (/today, /plan/week) → index.html; /assets/*.js + /strava/*.svg served; /api/health,/api/status → JSON; /api/nonexistent, /favicon.ico → 404; /q/health untouched.
- Quinoa rejected: no release supports Quarkus 3.37.
### Remaining
- [ ] PR + code-review + CI (native+parity)
- [ ] Deploy backend-v2, verify SPA on its own domain
- [ ] Domain swap iron-trainer.up.railway.app → backend-v2 (confirm with Viktor first)
- [ ] Decommission FastAPI ([[iron-trainer-foi1]])

## Code-review fixes 2026-07-20
- **[CONFIRMED #3]** Mapper is app-wide and intercepted intentional /api NotFoundExceptions (export/nutrition/job/tests) — now passes the original response through untouched (`e.getResponse()`) for all non-SPA cases, exact parity with the no-mapper default.
- **[CONFIRMED #1]** Non-GET (POST/PUT/DELETE) to unmatched non-api paths returned 200 index.html; now GET/HEAD-only fallback (matches StaticFiles(html=True)), other verbs pass through to 404.
- **[CONFIRMED #2 + efficiency #7]** Added `StaticCacheHeaders` (core vertx-http Filters hook): index.html/`/` → no-cache (always fresh after redeploy), /assets/* hashed files → immutable 1yr. API paths untouched.
- Accepted by design: unmatched non-api GET → SPA shell (#4, inherent to a SPA front door); dotted client routes → 404 (#5, no such routes in this app); q/ guard kept as harmless defensive net (#6).
- Verified locally: POST/DELETE→404, /today→index, /api 404s pass through, cache headers correct.
