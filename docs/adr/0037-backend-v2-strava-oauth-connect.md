# 0037 — Backend v2: Strava OAuth part 1 — session minting + connect (2026-07-19)

Date: 2026-07-19
Status: Accepted
Epic: iron-trainer-eom4 (Phase 7) · Feature: iron-trainer-xtre (part 1) · Pattern: ADR 0020 / 0022

## Context

Strava OAuth is the last major API vertical FastAPI still owns, and the key
unblocker for the Phase-7 front-door cutover: it's where the session cookie is
MINTED (login). backend-v2's `SessionCookie` (ADR 0022) only VERIFIES the
itsdangerous-signed Starlette cookie — it could not mint one. This slice adds the
minting primitive (the security-critical, hard part) + the `connect` endpoint.
The `callback` (login) + `disconnect` are the next slice, given the login's
security sensitivity warrants its own focused review.

## What was built

- **`SessionCookie.sign` + `read`** — the exact inverse of the existing verify:
  `base64(json.dumps(session)) "." base64url(minimal-BE timestamp) "."
  base64url(HMAC-SHA1(key, payload.ts))`, itsdangerous 2.x key derivation. `sign`
  uses `PyJson.dumps` (", "/": " spacing) so the base64 payload matches Starlette
  `json.dumps` — the output is **byte-identical to Python** (proven against the
  existing pinned test vector: `sign({"athlete_id": 7}, "test-secret-key",
  1700000000)` reproduces the Python-signed cookie exactly). `read` returns the
  full verified session (for `oauth_state`).
- **`StravaOAuth`** — `authorizeUrl(state)` (byte-parity param order/encoding vs
  Python `urlencode`), `configured()`, `newState()` (`secrets.token_urlsafe(16)`).
- **`StravaResource.connect`** — `GET /api/strava/connect`: 400 when not
  configured; else mint a session carrying `oauth_state` and 307-redirect to
  Strava consent, setting the `session` cookie (Path=/, Max-Age 14d, HttpOnly,
  SameSite=Lax, Secure per `COOKIE_SECURE`). Port of `strava_router.connect`.

## Why byte-identical minting matters

During the strangle window both backends read the same `session` cookie. A cookie
minted by backend-v2's `connect` must be verifiable by FastAPI's `callback` (and
vice-versa). Reproducing the itsdangerous/Starlette bytes exactly guarantees
cross-backend interoperability and no re-login — this is the security-critical
invariant, hence the byte-identity test.

## Testing

- **`SessionCookieTest`** (extended): `sign` reproduces the Python vector
  byte-for-byte; a mint→`read`→`athleteId` round-trip; wrong-secret rejection.
- **`StravaConnectTest`** (`@QuarkusTest`): `connect` → 307 to the Strava
  authorize URL (deterministic params) with a signed `session` cookie carrying a
  `token_urlsafe(16)` state. (Env-robust — the shell may inject a real
  `STRAVA_CLIENT_ID`/`SESSION_SECRET`.)
- Full v2 suite: 153 green.

## Deploy / flip notes

- `connect` needs `STRAVA_CLIENT_ID` (+ `STRAVA_REDIRECT_URI`) on backend-v2 to
  build the authorize URL — wire them as cross-service references (like
  `ANTHROPIC_API_KEY`/`SESSION_SECRET`) when this vertical is flipped. Dormant
  until then (not in `proxy_paths`).
- **Do NOT flip `/api/strava/connect` until `callback` (next slice) is also on
  backend-v2**, or a connect-on-v2 → callback-on-FastAPI split must be verified
  (it works via the byte-identical cookie, but flip both together to be safe).

## Next

Part 2 (xtre): `GET /callback` (state check → code→token exchange → allowlist →
find-or-create athlete → mint `athlete_id` login session → save tokens) and
`POST /disconnect` (deauthorize + Strava-data deletion). Then flip.
