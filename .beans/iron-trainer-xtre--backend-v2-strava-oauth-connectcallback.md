---
# iron-trainer-xtre
title: 'backend-v2: Strava OAuth connect/callback'
status: in-progress
type: feature
priority: normal
created_at: 2026-07-16T23:53:05Z
updated_at: 2026-07-19T23:10:26Z
---

Port GET /api/strava/connect (authorize_url redirect) + GET /api/strava/callback (exchange_code → save_tokens → seed_profile). Web/cookie surface + STRAVA_CLIENT_SECRET → needs the Phase 7 session-auth seam [[iron-trainer-eom4]] and secret handling. Deferred from [[iron-trainer-3ptl]].

## Part 1 shipped (ADR 0037): session minting + connect
- SessionCookie.sign + read: mint the itsdangerous/Starlette session cookie, BYTE-IDENTICAL to Python (proven vs the pinned test vector). read() returns the full session (oauth_state).
- StravaOAuth: authorizeUrl (byte-parity urlencode) + configured() + newState().
- StravaResource.connect: GET /api/strava/connect → mint oauth_state cookie + 307 to Strava consent. config: strava.redirect-uri, cookie-secure.
- Tests: SessionCookieTest (byte-identical mint + round-trip) + StravaConnectTest. v2 suite 153 green.
STILL TODO (part 2): GET /callback (login: state→exchange→allowlist→find_or_create→mint athlete_id session→save_tokens) + POST /disconnect (deauthorize + data deletion). Then flip (both together). Deploy: wire STRAVA_CLIENT_ID/SECRET/REDIRECT_URI to backend-v2.
