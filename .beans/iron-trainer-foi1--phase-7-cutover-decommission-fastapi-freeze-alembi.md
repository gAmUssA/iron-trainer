---
# iron-trainer-foi1
title: 'Phase 7 cutover: decommission FastAPI, freeze Alembic'
status: todo
type: task
priority: normal
created_at: 2026-07-17T04:45:21Z
updated_at: 2026-07-20T18:27:13Z
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
