---
# iron-trainer-ggmh
title: iOS in-app Sign in with Apple button
status: todo
type: feature
priority: low
created_at: 2026-07-29T12:51:13Z
updated_at: 2026-07-29T12:51:13Z
---

Web Sign in with Apple shipped (bean 3e6w, ADR 0052). The iOS in-app SIWA button was DEFERRED: the app authenticates via device pairing (no in-app third-party login), so App Store Guideline 4.8 likely doesn't apply, and a Strava-free account is degraded on the Strava-centric iOS app.

## When to do
- If App Store review flags 4.8, or for a Strava-free iOS onboarding path.

## Plan
- [ ] SignInWithAppleButton (AuthenticationServices) per developer.apple.com/documentation/signinwithapple → identityToken.
- [ ] POST identityToken to /api/auth/apple (native bearer endpoint — ALREADY BUILT) → store the device bearer.
- [ ] Enable the applesignin capability + entitlement on App ID io.gamov.irontrainer.helper (the capability is already enabled for web grouping; add the app entitlement).
- [ ] Device-test via TestFlight (SIWA needs a real device/Apple ID).

Backend is done (multi-audience AppleAuth accepts the native bundle id). This is iOS-only work. [[iron-trainer-3e6w]]
