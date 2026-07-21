---
# iron-trainer-3e6w
title: Sign in with Apple — Strava-free auth (iOS + backend)
status: todo
type: feature
priority: high
created_at: 2026-07-21T23:54:20Z
updated_at: 2026-07-21T23:54:45Z
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
