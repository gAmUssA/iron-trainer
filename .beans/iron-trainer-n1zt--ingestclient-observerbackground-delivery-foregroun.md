---
# iron-trainer-n1zt
title: IngestClient + observer/background delivery + foreground catch-up + Sync-now
status: completed
type: task
priority: normal
created_at: 2026-07-21T15:41:54Z
updated_at: 2026-07-21T18:29:50Z
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

## Code review fixes (2026-07-21) — 3 data-loss bugs
Review caught silent permanent data loss in the anchored-delta design. Reworked to full-window re-read:
- [0/3 CONFIRMED] anchor advanced past unsent/dropped/deleted samples (HRV w/o sleep window → assembled to nothing → anchor still committed → lost). FIX: sync() now full-reads the last 7 days (HealthKitReader.recentSamples), assembles, POSTs — NO anchors. Self-healing (HAE-style overlapping re-send); edits/deletions reflected; backend upsert dedupes.
- [2 CONFIRMED] HTTP 200 {days:0} on revoked bearer advanced anchors past unstored data. FIX: post() returns stored-day count; sync treats stored==0 (records sent) as failure + surfaces it; next sync retries.
- [1 CONFIRMED] observers.isEmpty guard INSIDE the per-type loop → only ONE observer for an arbitrary type → background delivery never woke for HRV/RHR/sleep. FIX: once-guard wraps the whole loop → one HKObserverQuery per type.
- [4] Settings spinner now binds published isSyncing (dropped duplicated nativeSyncBusy).
- [5] storage keys shared from AuthModel (static tokenAccount/serverKey) — no drift.
Ceiling documented: a pure-delete (no replacement) leaves the field omitted → backend keeps old value; delete-and-replace (RHR) IS handled. Anchored reader retained as future incremental optimization (unused by v1). Rebuild: 13 tests, 0 failures.
