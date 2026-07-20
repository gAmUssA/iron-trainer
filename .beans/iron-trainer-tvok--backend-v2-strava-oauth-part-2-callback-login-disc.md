---
# iron-trainer-tvok
title: 'backend-v2: Strava OAuth part 2 — callback (login) + disconnect'
status: in-progress
type: feature
priority: high
created_at: 2026-07-19T23:35:51Z
updated_at: 2026-07-20T00:08:59Z
---

Second slice of the Strava OAuth migration (xtre part 1 shipped connect + session-cookie minting, PR #77).

## Scope
- GET /api/strava/callback (the LOGIN path): verify oauth_state (CSRF, from the session cookie minted by connect) → exchange_code(code) for tokens → is_allowed() allowlist gate → find_or_create_athlete → mint an athlete_id LOGIN session (SessionCookie.sign, proven byte-identical) → save_tokens → redirect to frontend origin.
- POST /api/strava/disconnect: deauthorize with Strava + delete stored Strava tokens/data for the athlete.

## Why
callback is where LOGIN actually happens — the last piece before /api/strava/connect+callback can be flipped together at the front door (Phase-7 cutover unblocker). Minting primitive already exists (SessionCookie.sign, part 1).

## Then
- Wire STRAVA_CLIENT_ID / STRAVA_CLIENT_SECRET / STRAVA_REDIRECT_URI to backend-v2 (cross-service refs, like ANTHROPIC_API_KEY/SESSION_SECRET).
- Add parity tests (callback state-mismatch 400, allowlist reject, disconnect).
- Flip connect + callback TOGETHER (never split).

Blocked-by: none (part 1 merged). Pattern: worktree + ADR.

## Implementation
- GET /callback (login): oauth_state CSRF → exchangeCode → allowlist → find-or-create athlete → mint athlete_id session → save tokens; denied/no-code/exchange-fail/invalid-state/not-allowed → SPA redirect. Local mode → default athlete.
- POST /disconnect: best-effort deauthorize → purge activities+metrics+device tokens → clear tokens/name → §2.5 summary.
- StravaOAuth: exchangeCode/deauthorize/isAllowed/authRequired/frontendOrigin. StravaApi: exchangeCode + deauthorize.
- PARITY FIX: Set-Cookie now byte-matches Starlette (unquoted, no Version=1) — was RFC-2109 quoted via NewCookie; also fixes part-1 connect (dormant).
- Config: irontrainer.auth-required, allowed-strava-ids; frontend-origin default aligned to localhost:5173.
- Tests: StravaCallbackTest (local), StravaCallbackAuthTest (auth+allowlist login mint), StravaDisconnectTest, + callback error-redirect parity (verified vs real backends). v2 suite 159 green.
- ADR 0038.
