---
# iron-trainer-3e6w
title: Sign in with Apple — Strava-free auth (iOS + backend)
status: in-progress
type: feature
priority: critical
created_at: 2026-07-21T23:54:20Z
updated_at: 2026-07-23T03:12:04Z
blocking:
    - iron-trainer-k5d0
---

Add Sign in with Apple as a parallel identity provider so users can create/sign into an account WITHOUT Strava — the clean Strava-free path (SIWA + Apple Health = zero Strava). Supports the §5.3 decouple ([[iron-trainer-k5d0]]) and is likely REQUIRED for public App Store release (Guideline 4.8: an app whose primary account is authed via a third-party service — ours is Strava-backed — must also offer a privacy-preserving login; SIWA qualifies).

Difficulty: MODERATE (~1 focused session, iOS-first). Slots into the existing auth structure — Athlete.stravaAthleteId + Devices/DeviceToken bearer minting + SessionCookie already exist; SIWA is a parallel provider minting the SAME bearer (same shape Strava OAuth follows).

## Scope (iOS-native first)
- [ ] iOS: AuthenticationServices — SignInWithAppleButton → identity token; POST to a new backend endpoint for a device bearer. Apple provides the whole UI/flow (~1-2h).
- [x] Backend: POST /api/auth/apple — AppleAuth verifies the JWT via Apple's JWKS (nimbus-jose-jwt), checks iss/aud/exp, reads sub; AppleResource find-or-creates by appleUserId + mints a bearer (device/claim shape). 2 smoke tests pass.
- [x] Account: find-or-create Athlete by apple_user_id; mint bearer via Devices.createBearerToken.
- [x] DB: apple_user_id column + V3 migration (partial unique index). Prod: manual Supabase ALTER before deploy still required.
- [ ] Apple Developer: enable the Sign in with Apple capability on App ID io.gamov.irontrainer.helper (native needs no Service ID/key — verify against Apple's public keys).

## Deferred
- Web SIWA (Service ID + private key + redirect flow) — another ~half session; iOS-first ships value + satisfies 4.8 for the app.
- Account linking (attach Apple id to an existing Strava-created account, and vice-versa).

Verify on device via TestFlight (SIWA needs a real device/Apple ID). Follows the worktree → build → PR → review flow.

## Prioritized 2026-07-22 (Viktor) — implement next.

## Account linking (2026-07-22, per Viktor)
AppleResource links the Apple id to the CURRENT authenticated athlete: (1) known Apple id → that athlete; (2) authenticated (e.g. Strava session) + current athlete has no Apple id → LINK (Strava+Apple = one account); (3) else fresh account. So a Strava user who signs in with Apple while logged in gets linked, not forked. Reverse direction (Apple-first → connect Strava) = [[iron-trainer-4uj1]] (touches the parity-sensitive Strava callback; deferred). Merging two pre-existing accounts = out of scope.

## Security review fixes (2026-07-22) — 2 account-takeover bugs
- [0/1 CRITICAL] linking used current.idOrNull() which falls back to the DEFAULT athlete (id 1) when auth-required is off, and accepted any bearer (incl. ingest tokens) → anonymous sign-in could hijack the owner account / ingest token could escalate. FIX: linking now gated on a genuine LOGIN bearer — an actual Authorization: Bearer header (no header → no link, so the default fallback never links) + a valid, non-ingest token. Regression test AppleLinkingTest (anonymous sign-in creates a fresh account, does not hijack an existing athlete).
- [2] concurrent double-tap create raced the apple_user_id unique index → 500. FIX: persistAndFlush + catch → 409 (client retries, finds the winner's athlete).
- [3] AppleAuth collapsed JWKS-outage into 401 (looked like a bad token). FIX: KeySourceException → 503 (retriable), other → 401.
- [5] parsed Apple email but never used → dropped it (AppleId is just sub).
- [4] no throttle: accepted — creation requires a valid Apple-signed token (not a guessable code); documented.
All tests pass.

## Web SIWA frontend + Flyway (implemented)
- appleSignIn.ts: Apple JS SDK loader + getAppleIdentityToken() (popup, clientId=io.gamov.irontrainer.web, redirectURI=https://www.irontrainer.app/).
- AppleButton (HIG: black/light, white/dark, official logo).
- LoginScreen: 'Sign in with Apple' button (+ 'or' divider; works with or without Strava configured).
- AppleLinkCard in Settings: 'Continue with Apple' links Apple onto the logged-in athlete.
- api.appleWeb(idToken) → POST /api/auth/apple/web (credentials:include).
- Flyway ON in prod, baseline-version=2 (V1/V2 already manual in Supabase) → V3 auto-applies on deploy.
- APPLE_AUDIENCES env staged in Railway (helper,web).
- ⚠ clientId/redirectURI must match Viktor's Apple Services ID + Return URL exactly.
