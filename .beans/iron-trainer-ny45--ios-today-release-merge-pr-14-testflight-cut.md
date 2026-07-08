---
# iron-trainer-ny45
title: 'iOS Today release: merge PR #14 + TestFlight cut'
status: in-progress
type: feature
priority: high
created_at: 2026-07-08T17:29:24Z
updated_at: 2026-07-08T17:37:31Z
---

PR #14 (feature/ios-today-widgets-glass) is green, twice Copilot-reviewed, and HELD awaiting Viktor's merge command. Contains: Today view with interval-profile chart, race-countdown + today's-workout widgets (new IronTrainerWidgets target + App Group), Liquid Glass pass. ADR 0012.

- [x] Viktor: merge PR #14 (merged as 06c46ea, 2026-07-08)
- [x] fastlane beta → TestFlight: build 0.1.0 (202607081335) uploaded 2026-07-08; App Group auto-provisioned, entitlement verified on both binaries
- [ ] On-device: widgets on home + lock screen, Send to Watch, widget refresh after in-app plan reload
