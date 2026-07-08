---
# iron-trainer-ny45
title: 'iOS Today release: merge PR #14 + TestFlight cut'
status: in-progress
type: feature
priority: high
created_at: 2026-07-08T17:29:24Z
updated_at: 2026-07-08T17:29:24Z
---

PR #14 (feature/ios-today-widgets-glass) is green, twice Copilot-reviewed, and HELD awaiting Viktor's merge command. Contains: Today view with interval-profile chart, race-countdown + today's-workout widgets (new IronTrainerWidgets target + App Group), Liquid Glass pass. ADR 0012.

- [ ] Viktor: merge PR #14
- [ ] fastlane beta → TestFlight (watch for one-time App Group provisioning during signing)
- [ ] On-device: widgets on home + lock screen, Send to Watch, widget refresh after in-app plan reload
