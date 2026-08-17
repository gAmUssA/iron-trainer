---
# iron-trainer-5gep
title: 'iOS: load the plan automatically at launch so Today is always available'
status: completed
type: feature
priority: high
created_at: 2026-08-16T23:38:45Z
updated_at: 2026-08-16T23:40:20Z
---

The iOS app opened to "No workout yet" on every cold start, even for a signed-in
athlete with a plan. The Today view only appeared after tapping **Load my plan** —
`ImportModel.state` began `.empty` and nothing fetched until the button, the
toolbar refresh, or a pairing deep link fired.

Make the plan load itself at launch so Today is always there.

## Todo
- [x] Auto-load the plan at launch (no button) when signed in
- [x] Cache the last plan on disk so Today renders instantly AND offline
- [x] An automatic load must not hijack the user's context (a .itw opened via
      onOpenURL races the launch fetch) or dead-end them in a full-screen error
- [x] Refresh on foreground so an overnight-backgrounded app isn't showing yesterday
- [x] Clear the cached plan on sign-out (athlete-specific data)
- [x] Tests

## Summary of Changes

ADR 0068. Built in worktree `ios-auto-load-plan`.

- **PlanCache.swift** (new) — plan persisted to Application Support (not Caches: iOS may purge Caches at exactly the moment you are offline), excluded from backup, cleared on sign-out. `TrainingPlan` gained `Codable`; both members already were, so that was the whole cost of an offline Today view.
- **ImportModel** — restores the cached plan in `init`, not a `.task` (a `.task` restore flashes the empty state on every launch); new `autoLoadPlan` kept distinct from `loadPlan` because they differ in behaviour, not degree; `isAutoReplaceable` refuses to clobber `.loaded` / `.scheduled` / `.failed`; `planLoadGen` makes the last of overlapping loads win; all three fetch paths funnel through one `adopt()` so the cache cannot drift out of sync with the screen.
- **ContentView** — `.task` loads at launch, `.onChange(of: scenePhase)` refreshes on foreground (gated so a cold start does not fetch twice); the empty state carries the reason when an automatic load came up dry.
- **SettingsView** — sign-out calls `forgetPlan()` before `signOut()`.

## Verification

13 new tests. Full iOS suite 29 passed / 0 failed (was 16). Builds clean for iOS Simulator.

NOT verified: end-to-end launch against a live paired server on device — the network path is covered only by a stubbed URLProtocol. Worth one manual cold-start-in-airplane-mode pass on the next TestFlight build.
