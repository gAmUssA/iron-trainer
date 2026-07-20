# 0038 — Backend v2: Strava OAuth part 2 — callback (login) + disconnect (2026-07-19)

Date: 2026-07-19
Status: Accepted
Epic: iron-trainer-eom4 (Phase 7) · Feature: iron-trainer-tvok (part 2) · Pattern: ADR 0020 / 0022 / 0037

## Context

Part 1 (ADR 0037) shipped the security-critical primitive — byte-identical
session-cookie **minting** — plus `GET /connect`. This slice completes the Strava
OAuth vertical: `GET /callback` (where LOGIN actually happens) and
`POST /disconnect`. With both on backend-v2, `connect`+`callback` can be flipped
together at the front door — the last major FastAPI-owned vertical before the
Phase-7 cutover.

## What was built

- **`GET /api/strava/callback`** — port of `strava_router.callback`:
  - denied / no-code → 307 SPA redirect with `strava_error`;
  - **auth mode**: verify `oauth_state` (CSRF, from the session cookie `connect`
    minted) → exchange the code → allowlist-gate the Strava id → find-or-create
    the athlete → mint an `athlete_id` **login session** → save tokens;
  - **local mode**: attach the tokens to the default athlete (no new users).
  - All outcomes 307-redirect to the SPA (`{cors_origin_list[0]}/?flag`).
- **`POST /api/strava/disconnect`** — port of `strava_router.disconnect`:
  revoke access at Strava (best-effort — already-revoked/gone still purges),
  then delete the athlete's activities + derived `metrics_daily` + device tokens
  and clear the stored Strava tokens/name. Returns the deletion summary (§2.5).
- **`StravaOAuth`** grew `exchangeCode`, `deauthorize`, `isAllowed` (allowlist
  parity with Python's `tok.isdigit()` filter), `authRequired()`, and
  `frontendOrigin()`. **`StravaApi`** grew `exchangeCode` (authorization_code
  grant) + `deauthorize`.

## The cookie-serialization parity fix (the notable find)

While testing the login-session mint, the outgoing `Set-Cookie` was
`session="<v>"; Version=1; ...` — JAX-RS `NewCookie` quotes the value and appends
`Version=1` (RFC 2109). Starlette emits `session=<v>; path=/; Max-Age=1209600;
httponly; samesite=lax` — **unquoted, no `Version=1`**. During the strangle window
both backends set the same cookie for the same session, so a divergent
serialization is a real interop bug (a browser could round-trip the quoted form
back in a shape FastAPI's verify rejects → login breaks cross-backend).

Fix: mint the `Set-Cookie` as a **raw header string** byte-matching Starlette
exactly, not via `NewCookie`. This also corrects part-1 `connect` (same shared
helper) — it was latent because `connect` is still dormant (not flipped).

## Config

New v2 properties (same env vars FastAPI's Settings reads, same defaults):
`irontrainer.auth-required` (`AUTH_REQUIRED`), `irontrainer.allowed-strava-ids`
(`ALLOWED_STRAVA_IDS`). `strava.frontend-origin` default aligned to
`http://localhost:5173` (Settings.cors_origins) so dev/test/parity redirect
Locations agree. All Optional/defaulted so an unset env never fails native boot
(the SmallRye empty-string trap from ADR 0037).

## Testing

- **`StravaCallbackTest`** (local mode): denied/no-code redirects; a code
  exchange connects the default athlete (no session minted locally).
- **`StravaCallbackAuthTest`** (`@TestProfile` auth-required + allowlist):
  invalid-state rejection; a full login mints a verifiable `athlete_id` session
  (oauth_state consumed) and persists the find-or-created athlete with the
  exchanged tokens + name.
- **`StravaDisconnectTest`**: not-connected still purges and returns the summary.
- **Parity** (`test_strava_callback_error_redirect_parity`): the denied/no-code
  branches redirect to the **identical** Location on both backends (verified
  against real FastAPI + backend-v2; the exchange happy path needs live Strava).
- Full v2 suite: 159 green.

## Deploy / flip notes

- `callback`/`connect` need `STRAVA_CLIENT_ID` / `STRAVA_CLIENT_SECRET` /
  `STRAVA_REDIRECT_URI` on backend-v2 (cross-service refs, like
  `ANTHROPIC_API_KEY`/`SESSION_SECRET`) — wire them at flip time. In prod
  (`AUTH_REQUIRED=true`) also confirm `ALLOWED_STRAVA_IDS` matches FastAPI's.
- **Flip `connect` + `callback` together** (never split): the byte-identical
  cookie makes a split technically safe, but flipping both removes the last doubt.
- WireMock test resources are now `restrictToAnnotatedClass=true` so the login
  stub (token WITH athlete) can't leak into the sync/dedup tests (token WITHOUT).

## Next

Flip `connect`+`callback` (+ optionally `disconnect`) at the front door; then the
GDPR archive import (bean f6ui) is the remaining Strava slice before Phase-7
decommission (bean foi1).
