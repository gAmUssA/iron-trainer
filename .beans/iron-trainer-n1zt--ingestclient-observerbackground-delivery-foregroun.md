---
# iron-trainer-n1zt
title: IngestClient + observer/background delivery + foreground catch-up + Sync-now
status: todo
type: task
priority: normal
created_at: 2026-07-21T15:41:54Z
updated_at: 2026-07-21T17:50:37Z
parent: iron-trainer-yrsz
---

IngestClient emits the HAE-shaped payload (bearer auth) to POST /api/health/ingest — zero backend changes. Delivery: HKObserverQuery + enableBackgroundDelivery(.hourly) registered in App init → on fire, run anchored query → POST → advance anchor after confirm → ALWAYS call observer completion (3 misses = iOS disables delivery). Plus foreground catch-up on scenePhase.active + a manual 'Sync now' row. Background delivery doesn't work in Simulator → device test.

## Summary of Changes (2026-07-21)
Ties reader (aw8t) + assembler (90iu) to /api/health/ingest — native sync is now real. Zero backend changes.
- HealthIngestClient: pure payload builder (HAE-shaped, maps to HealthIngest.FIELD names + sleep_analysis record; ONLY non-nil fields → safe under per-field upsert) + POST (bearer). 3 payload unit tests.
- NativeHealthSync (@MainActor singleton): sync() = delta-read all types → assemble → POST → commit anchors ONLY on success (at-least-once; failed POST re-sends). Self-contained auth (UserDefaults/Keychain) for background wakeups.
- Delivery: HKObserverQuery + hourly enableBackgroundDelivery per type, registered at App.init; foreground catch-up on scenePhase.active; manual 'Sync now' in Settings; observers always call completion (defer).
- Wiring: IronTrainerApp init + scenePhase; SettingsView Apple Health card gains Sync-now + last-native-sync + triggers register+sync right after the grant.
- ADR 0051. xcodebuild test (iPhone 16 sim): 13 tests (10 assembler + 3 payload), 0 failures.
Device/TestFlight verifies background delivery + real reads (Simulator can't); backend logs will then show native POST /api/health/ingest.
