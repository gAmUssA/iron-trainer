---
# iron-trainer-3e6w
title: Sign in with Apple — Strava-free auth (iOS + backend)
status: in-progress
type: feature
priority: critical
created_at: 2026-07-21T23:54:20Z
updated_at: 2026-07-22T22:23:48Z
blocking:
    - iron-trainer-k5d0
---

Add Sign in with Apple as a parallel identity provider so users can create/sign into an account WITHOUT Strava — the clean Strava-free path (SIWA + Apple Health = zero Strava). Supports the §5.3 decouple ([[iron-trainer-k5d0]]) and is likely REQUIRED for public App Store release (Guideline 4.8: an app whose primary account is authed via a third-party service — ours is Strava-backed — must also offer a privacy-preserving login; SIWA qualifies).

Difficulty: MODERATE (~1 focused session, iOS-first). Slots into the existing auth structure — Athlete.stravaAthleteId + Devices/DeviceToken bearer minting + SessionCookie already exist; SIWA is a parallel provider minting the SAME bearer (same shape Strava OAuth follows).

## Scope (iOS-native first)
- [ ] iOS: AuthenticationServices — SignInWithAppleButton → identity token; POST to a new backend endpoint for a device bearer. Apple provides the whole UI/flow (~1-2h).
- [ ] Backend: new endpoint (e.g. POST /api/auth/apple). Verify Apple's JWT — fetch Apple's JWKS (https://appleid.apple.com/auth/keys), validate iss=https://appleid.apple.com, aud=our bundle id, exp; read stable `sub`. No JWT infra exists yet → add a lightweight JWKS verify (or quarkus-oidc).
- [ ] Account: find-or-create Athlete by a new nullable `apple_user_id` column; handle Apple's private-relay email. Mint a bearer (reuse Devices) / session.
- [ ] DB: add apple_user_id column + Flyway migration. NOTE the prod gotcha — migrate-at-start is OFF in prod, so manual ALTER on Supabase BEFORE the image cuts over ([[backend-v2-railway-deploy]]).
- [ ] Apple Developer: enable the Sign in with Apple capability on App ID io.gamov.irontrainer.helper (native needs no Service ID/key — verify against Apple's public keys).

## Deferred
- Web SIWA (Service ID + private key + redirect flow) — another ~half session; iOS-first ships value + satisfies 4.8 for the app.
- Account linking (attach Apple id to an existing Strava-created account, and vice-versa).

Verify on device via TestFlight (SIWA needs a real device/Apple ID). Follows the worktree → build → PR → review flow.

## Prioritized 2026-07-22 (Viktor) — implement next.

## Decision (2026-07-22, Viktor): WEB-FIRST, iOS deferred
SIWA is needed for athletes without/not-wanting Strava. Do it on the WEBSITE now; DEFER the iOS button.

### Web (current work)
Web SIWA differs from native: uses **Sign in with Apple JS** (AppleID.auth) with a **Service ID** audience (e.g. io.gamov.irontrainer.web), NOT the app bundle id, and mints a **web SESSION COOKIE** (like the Strava web login), not a device bearer.
- [ ] Backend: AppleAuth accepts MULTIPLE audiences (native bundle id + web Service ID) via config. Add a web sign-in that verifies the id_token then mints a session cookie (reuse SessionCookie, mirror StravaResource's session mint) + find-or-create/link (existing logic).
- [ ] Frontend: Sign in with Apple JS button on LoginScreen → get authorization.id_token → POST → session set → signed in. Follow Apple's official docs + button styles.
- [ ] Apple Developer (Viktor): create a Service ID (io.gamov.irontrainer.web), enable Sign in with Apple, set the return URL to the web origin. (id_token-only verify needs no private key.)

### iOS (DEFERRED — plan for later)
Not needed yet: the iOS app authenticates via device pairing (no in-app third-party login button), so App Store Guideline 4.8 likely doesn't apply; and a Strava-free account is degraded on the Strava-centric app. Revisit if App Store review flags 4.8, or for a Strava-free iOS onboarding.
Plan when resumed: SignInWithAppleButton (AuthenticationServices) per developer.apple.com/documentation/signinwithapple → identityToken → POST /api/auth/apple (the native bearer endpoint, already built) → store bearer. Enable the applesignin capability + entitlement on App ID io.gamov.irontrainer.helper. Device-test via TestFlight.
