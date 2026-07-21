---
# iron-trainer-foi1
title: 'Phase 7 cutover: decommission FastAPI, freeze Alembic'
status: completed
type: task
priority: normal
created_at: 2026-07-17T04:45:21Z
updated_at: 2026-07-21T23:00:26Z
parent: iron-trainer-eom4
---

Route all traffic to backend-v2, retire FastAPI + strangler, freeze Alembic (Flyway becomes source of truth).

## Strava vertical — cutover readiness (2026-07-20)
Entire Strava surface is ported to backend-v2 (connect/callback/disconnect/sync/dedup/import). backend-v2 is now fully Strava-CONFIGURED in prod (cross-service refs from the iron-trainer service): STRAVA_CLIENT_ID/SECRET/REDIRECT_URI, CORS_ORIGINS, ALLOWED_STRAVA_IDS, AUTH_REQUIRED=true, COOKIE_SECURE=true, SESSION_SECRET. Verified: GET /api/strava/connect → 307 to strava.com/oauth/authorize with the prod redirect_uri.

### No traffic flipped (deliberate — avoid split-brain)
Decision 2026-07-20: hold ALL remaining Strava write-flips; cut the whole vertical over at the front-door swap. dedup remains flipped (pre-existing); sync/disconnect NOT flipped.

### These CANNOT strangler-flip — they MUST route to backend-v2 at the front-door swap:
- connect + callback (browser GET login): the strangler read-proxy (strangler.py:_proxy_read) is BEARER-ONLY and forwards NO Cookie (fetch() sends only Authorization). Browser session GETs stay local by design, and callback needs the incoming oauth_state cookie forwarded — impossible via the read-proxy. Login only works on backend-v2 when it IS the front door.
- import (POST multipart): the write-proxy buffers the ENTIRE body in the FastAPI process (_read_body) before forwarding → a large export (100s MB–2GB) OOMs the front door. Route directly to backend-v2 (streams to disk).

### Cutover checklist for Strava
- [ ] Front door = backend-v2 (DNS/routing) OR proxy all remaining /api/strava/* to it.
- [ ] Confirm STRAVA_REDIRECT_URI still points at the public callback host after the swap.
- [ ] Verify a real login end-to-end (connect → Strava consent → callback → athlete_id session cookie).
- [ ] Verify a real disconnect + a real archive import.

## PORT COMPLETE (2026-07-20)
Every FastAPI endpoint (all 52) is now ported to backend-v2, parity-tested, and deployed. Verified by the endpoint diff: ZERO FastAPI-only endpoints remain. The Phase-7 CODE gate is CLEAR.

This session's tail (after the Strava vertical): p4co (app tail: health/status/me/logout/athlete-GET), 0jwt (health recovery+ingest), wksi (profile PUT), w5w0 (export zip), 3qcz (device pairing). PRs #80–#84, ADRs 0040–0044.

### What remains = OPERATIONAL cutover only (not code):
1. Front door = backend-v2 (DNS/routing swap) OR proxy all remaining /api/* to it. Login (connect/callback) + import can only route to backend-v2 directly (can't strangler-flip — see the Strava cutover notes above).
2. Wire STRAVA_* + CORS_ORIGINS + ALLOWED_STRAVA_IDS on backend-v2 (DONE — already configured).
3. End-to-end verify: real login, disconnect, import, device pairing, HealthKit ingest.
4. Decommission FastAPI, freeze Alembic.

## Decommission (safe stop) 2026-07-20
Viktor confirmed the app works on the Quarkus front door + chose **safe stop (reversible)**.
- [x] FastAPI deployment REMOVED (`railway down` on service iron-trainer/b9a5a044) — Python stopped, front door (backend-v2) unaffected (verified 200).
- [x] Prevent auto-revival: root railway.toml watchPatterns → sentinel (no main-push redeploys). FastAPI service + vars KEPT (SESSION_SECRET ref intact, rollback available).
- [x] Alembic FROZEN: backend/alembic/FROZEN.md + env.py banner (no code guard — env.py runs on startup via init_db, a guard would break rollback). Schema now owned by backend-v2 Flyway.
### Deferred (needs relinquishing rollback)
- [ ] Full delete of the FastAPI Railway service (requires migrating SESSION_SECRET off it first — MCP was unauthorized this session)
- [ ] Remove FastAPI code (backend/) from the repo

## Parity gate retired (2026-07-21)
FastAPI↔backend-v2 'parity' CI job removed from .github/workflows/backend-v2.yml (Viktor: not running FastAPI in parallel → backend-v2 is source of truth, stop maintaining FastAPI parity). backend/contract_tests/ + run_parity.sh remain as DEAD reference code — foi1 should delete them along with the FastAPI app. First intentional divergence: gy48 (swim rest intervals).

## Full decommission done (2026-07-21)
Deleted the entire FastAPI footprint: backend/ tree (app/alembic/contract_tests/tests/scripts, pyproject.toml, uv.lock, alembic.ini — 99 files), root FastAPI Dockerfile + railway.toml (rollback config). Removed the FastAPI-based CI jobs (ci.yml 'backend' pytest + 'contract' HTTP suite); ci.yml now builds only the frontend. Updated README (backend = Quarkus/backend-v2) + backend-v2.yml parity comment. 101 files / 14,705 lines removed. KEPT: backend-v2 (incl. Py.java/PyJson — the live output contract for web/iOS, used by 32 files), the stopped FastAPI Railway service + its SESSION_SECRET vars (Railway-side, untouched — backend-v2 still references them). Viktor: full decommission (backend-v2 is source of truth; rollback recoverable from git).
