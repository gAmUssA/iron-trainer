---
# iron-trainer-ny45
title: 'iOS Today release: merge PR #14 + TestFlight cut'
status: completed
type: feature
priority: high
created_at: 2026-07-08T17:29:24Z
updated_at: 2026-07-15T02:33:12Z
parent: iron-trainer-03qt
---

PR #14 (feature/ios-today-widgets-glass) is green, twice Copilot-reviewed, and HELD awaiting Viktor's merge command. Contains: Today view with interval-profile chart, race-countdown + today's-workout widgets (new IronTrainerWidgets target + App Group), Liquid Glass pass. ADR 0012.

- [x] Viktor: merge PR #14 (merged as 06c46ea, 2026-07-08)
- [x] fastlane beta → TestFlight: build 0.1.0 (202607081335) uploaded 2026-07-08; App Group auto-provisioned, entitlement verified on both binaries
- [x] On-device: confirmed by Viktor 2026-07-08 (build 202607081816) — back nav + widgets working

Phone test 2026-07-08: found 2 issues (no back button on workout detail; race widget blank white) — tracked in the phone-test-feedback bug bean. On-device validation continues after that fix ships.

## Summary of Changes

iOS Today release shipped across builds 202607081335 + 202607081816 (PRs #14, #15): Today view with interval profiles, race countdown, widgets, Liquid Glass pass, phone-test fixes. Confirmed working on device.
