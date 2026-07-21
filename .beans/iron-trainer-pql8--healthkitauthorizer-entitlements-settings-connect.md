---
# iron-trainer-pql8
title: HealthKitAuthorizer + entitlements + Settings 'Connect Apple Health' card
status: todo
type: task
priority: normal
created_at: 2026-07-21T15:41:54Z
updated_at: 2026-07-21T16:01:20Z
parent: iron-trainer-yrsz
---

Entitlements (healthkit + healthkit.background-delivery, NOT clinical records) + NSHealthShareUsageDescription. HealthKitAuthorizer requests read access in-context. Settings 'Connect Apple Health' card with last-sync + per-metric freshness. NEVER gate on authorizationStatus (read-denial is invisible) — empty states point user to Health → Sharing. Privacy policy covering health data (Guideline 5.1.3) + App Privacy label (Health & Fitness, linked to identity, no tracking).

## Summary of Changes (2026-07-21)
Foundation task — grants access + provisions the capability; no reading yet (that's aw8t/90iu/n1zt).
- Entitlement: added com.apple.developer.healthkit.background-delivery (healthkit + app-group already present).
- Info.plist: broadened NSHealthShareUsageDescription to name recovery metrics + readiness purpose (review requires match).
- HealthKitAuthorizer.swift (new): read-only auth for the v1 recovery type set (sleepAnalysis, HRV SDNN, restingHeartRate, respiratoryRate, sleeping wrist temp, bodyMass, VO2max). Requests all at once; NEVER gates on status (read-denial invisible) — persists only 'hasRequested'.
- SettingsView: new 'Apple Health' card (Connect/Review button → requestAuthorization); existing HAE section re-labelled 'Health Auto Export (legacy)' for the migration overlap.
- ADR 0048. xcodebuild simulator BUILD SUCCEEDED (x86_64+arm64). Device/TestFlight verification of the sheet + real grants is Viktor-driven.

## Code review fixes (2026-07-21)
Local high-effort review: 2 confirmed cleanup findings (no correctness bugs), both fixed:
- [0] 'Review Apple Health access' button re-called requestAuthorization() = silent no-op after first ask. Replaced cached hasRequested flag with statusForAuthorizationRequest sheet-gate (needsRequest); once .unnecessary, show 'manage in Health → Sharing' text instead of a dead button. More correct + future-proof (re-arms when v2 adds workout types).
- [1] NSHealthShareUsageDescription over-claimed 'workout heart rate' (not in readTypes). Trimmed to exactly the 7 requested types, aligned with the Settings footer.
Rebuild: BUILD SUCCEEDED.
