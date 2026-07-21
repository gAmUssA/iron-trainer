---
# iron-trainer-vof4
title: iOS readiness/recovery glance widget (extend snapshot pipeline)
status: in-progress
type: feature
priority: normal
created_at: 2026-07-21T13:01:16Z
updated_at: 2026-07-21T13:19:49Z
parent: iron-trainer-03qt
---

Add a quick-glance readiness/recovery WidgetKit widget to the Iron Trainer iOS app. ADR 0047. Extends the EXISTING snapshot pipeline (app fetches -> App Group group.io.gamov.irontrainer -> widget reads; no network/Keychain in the extension — Apple's recommended pattern). Existing: IronTrainerWidgets ships RaceCountdownWidget + TodayWorkoutWidget via SnapshotProvider + WidgetSnapshot.

## Plan
- WidgetSnapshot (ios/Shared/WidgetSnapshot.swift): add readiness{ call(hard/easy/rest), level(green/amber/red), hrvMs, rhrBpm, ctl, atl, tsb, reason, generatedAt }.
- ImportModel: on plan refresh, also fetch /api/metrics/readiness/today (+ latest /api/metrics/pmc row, /api/health/recovery?days=1 — app already has ReadinessToday/RecoveryDay types), populate snapshot.readiness, SharedStore.write, WidgetCenter.reloadAllTimelines().
- New ReadinessWidget: families .systemSmall (level color + big call word + HRV/RHR + sport dot), .accessoryCircular (level ring + call glyph), .accessoryRectangular (call + 'HRV 55 · RHR 48' + reason), .accessoryInline ('Rest · HRV 55'). .containerBackground(for:.widget) on all (iOS 17+, StandBy/Lock Screen).
- Timeline: reuse SnapshotProvider (midnight rollover; refresh on app write). Budget-safe.

## Todos
- [ ] Extend WidgetSnapshot + .sample
- [ ] Populate readiness in ImportModel (fetch + write)
- [ ] ReadinessWidget view (4 families) + register in WidgetBundle
- [ ] xcodegen + build for sim; device test (WidgetKit needs a real device)
- [ ] TestFlight

## Deferred
- Control Center control (Sync/Check-in) — interactive App Intent w/ network, later.
- iOS 26-only widget features — @available-gated if added.

Xcode build/test is Viktor-driven; I can write the Swift.



## Impl note (2026-07-21)
Branch feature/ios-readiness-widget. WidgetSnapshot gained a Readiness struct (call/level/hrvMs/rhrBpm/ctl/atl/tsb/reason) + optional readiness field (tolerant decode, back-compat). ImportModel writes the plan snapshot first, then augments with readiness fetched via PlanNetworkSource.readinessSnapshot() (readiness/today + pmc?days=1 + recovery) — readiness failure never costs the plan snapshot. New ReadinessWidget (systemSmall/accessoryCircular/accessoryRectangular/accessoryInline), registered in the bundle. xcodebuild for generic iOS Simulator: BUILD SUCCEEDED. HELD for Viktor: on-device build + TestFlight (WidgetKit needs a real device).
