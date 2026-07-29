---
# iron-trainer-3e6w
title: Sign in with Apple — Strava-free auth (iOS + backend)
status: completed
type: feature
priority: critical
created_at: 2026-07-21T23:54:20Z
updated_at: 2026-07-29T12:51:13Z
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

## Decision (2026-07-22, Viktor): WEB-FIRST, iOS deferred
SIWA is needed for athletes without/not-wanting Strava. Do it on the WEBSITE now; DEFER the iOS button. Web SIWA uses **Sign in with Apple JS** (AppleID.auth) with a **Service ID** audience (io.gamov.irontrainer.web), NOT the app bundle id, and mints a **web SESSION COOKIE** (like the Strava web login). iOS deferred: the app authenticates via device pairing (no in-app third-party login button), so Guideline 4.8 likely doesn't apply.

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

## Review fixes (2026-07-23) — local multi-agent review of PR #100
- CRITICAL: authenticated 'Link Apple' could switch the session to a different/empty account (data loss). resolveAthlete now link-only for authenticated callers (resolve to linkTarget or 409). +3 regression tests.
- sessionLinkTarget now uses the LAST session= cookie (matches BearerAuthFilter).
- apple.audiences default now includes the web Service ID.
- LoginScreen shows notice+error independently; AppleButton exposes aria-busy.

### iOS (DEFERRED — plan for later)
The iOS app authenticates via device pairing (no in-app third-party login button), so Guideline 4.8 likely doesn't apply. When resumed: SignInWithAppleButton (AuthenticationServices) → identityToken → POST /api/auth/apple (native bearer endpoint, already built) → store bearer. Enable the applesignin capability on App ID io.gamov.irontrainer.helper. Device-test via TestFlight.

## Prod SIWA debugging (2026-07-23) — two Apple-config gotchas
1. 'Sign Up Not Completed' AFTER the Apple sheet opens = Return URL exact-match fail. The Service ID had only https://irontrainer.app/ registered; frontend sent https://www.irontrainer.app/. Fix: register BOTH apex + www Return URLs.
2. 'Unable to post message to <www>. Recipient has origin <apex>' = in usePopup/web_message mode Apple posts the id_token back to the redirect_uri's ORIGIN, which must equal the PAGE origin. Hardcoded www redirectURI broke apex loads. Fix (PR #102): redirectURI = window.location.origin + '/' (matches whichever registered domain the user is on). Both irontrainer.app + www.irontrainer.app must be registered as Return URLs AND Domains. Live on both domains, deploy SUCCESS.

## Canonical domain cutover DONE (2026-07-29, PR #103)
Root cause of the linking pain was the 3-domain / host-scoped-cookie footgun. Fixed: irontrainer.app is now the single canonical origin. CanonicalHostRoute (Vert.x route, order -1000, gated on CANONICAL_HOST env) 301s www.irontrainer.app → irontrainer.app on every path. CORS_ORIGINS + STRAVA_REDIRECT_URI flipped to https://irontrainer.app; Apple Return URL + Strava callback domain moved to apex. Verified: www→apex 301 (root + /api paths), apex health 200, SIWA endpoint live. Viktor's account (athlete 2) already linked to Apple via DB.

## Summary of Changes (COMPLETED 2026-07-29)
Web Sign in with Apple shipped and verified in prod (ADR 0052): JWKS verify, native + web endpoints, account linking (link-only for authed callers, 409 on conflict, security-reviewed), web sign-in button + Settings link card, Flyway V3 auto-applied, native build fixed (Tink), redirectURI=page origin, canonical domain irontrainer.app (www→apex 301). Viktor's account linked + end-to-end verified (Sign in with Apple lands on the real account). iOS in-app SIWA button split to its own follow-up bean (deferred). Reverse link = 4uj1.
