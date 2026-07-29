---
# iron-trainer-aydv
title: Source-tag HealthKit samples by provenance (WHOOP/Watch/Oura)
status: todo
type: task
priority: normal
created_at: 2026-07-29T20:38:25Z
updated_at: 2026-07-29T20:50:35Z
parent: iron-trainer-ids6
---

Foundation for no-double-count + clean overlay. Our HealthKit ingestion should record each sample's SOURCE so WHOOP-via-HealthKit, Apple Watch, and Oura stay separable and readiness picks ONE source per metric instead of averaging duplicates.

## Todo
- [ ] On the iOS HealthKit read, capture sample.sourceRevision.source .name + .bundleIdentifier per sample; detect WHOOP by source.name=='WHOOP' (read the bundle id at RUNTIME — do not hardcode).
- [ ] Carry provenance into the ingest payload + store a source column on the recovery/metric rows.
- [ ] Readiness: choose a single source per metric (or expose per-source), never blend duplicates.
- [ ] Note: HealthKit HRV=SDNN vs WHOOP API HRV=RMSSD — never merge across those.
Check whether the current pipeline already carries any source attribution first.

## Current state (checked 2026-07-29)
Already shipped:
- iOS HealthKitReaders captures sample.sourceRevision.source.bundleIdentifier per sample (QuantitySample/SleepSample .sourceBundleID).
- iOS NightAssembler groups by sourceBundleID and picks ONE winning source per metric/night — NEVER blends ('Sources are never merged'). Sleep winner = most unioned asleep time; gauges = dominant in-window source; deterministic bundle-id tie-break. → cross-wearable de-dup is DONE at assembly.

GAP (the remaining work here):
- The winning source's identity is DROPPED before the backend: HealthSync.payload sends only {date, qty} (+ sleep stages), no source field.
- daily_recovery has NO source column (id, athlete_id, date, sleep_h/deep/rem/awake, sleep_start/end, hrv_ms, rhr_bpm, weight_kg, vo2max, respiratory_rate, wrist_temp_c).
So we can't currently LABEL which device a reading came from. Not a duplication bug (dedup already happens), but needed so the WHOOP overlay is a genuine 2-source comparison (else WHOOP-raw vs WHOOP-score).

Remaining (small, additive): add a source/provenance field to the ingest payload + a nullable source column on daily_recovery; carry the winning bundle id through NightAssembler → payload → store.
