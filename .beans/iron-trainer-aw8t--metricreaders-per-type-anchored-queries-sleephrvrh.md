---
# iron-trainer-aw8t
title: MetricReaders — per-type anchored queries (sleep/HRV/RHR/resp/temp/mass)
status: todo
type: task
priority: normal
created_at: 2026-07-21T15:41:54Z
updated_at: 2026-07-21T16:29:09Z
parent: iron-trainer-yrsz
---

One anchored HKAnchoredObjectQuery per type with a persisted HKQueryAnchor. Types: sleepAnalysis (allAsleepValues), heartRateVariabilitySDNN (spot samples → sleep-window mean), restingHeartRate (process HKDeletedObjects, recompute yesterday — Apple delete-and-replaces estimates), respiratoryRate, appleSleepingWristTemperature (read-only, Watch-only), bodyMass, vo2Max. Advance anchor ONLY after server confirms (at-least-once; backend upsert idempotent).

## Summary of Changes (2026-07-21)
Reader layer for native HealthKit ingestion (no POST/observers/aggregation — those are n1zt/90iu).
- AnchorStore: persist/load HKQueryAnchor per type (NSKeyedArchiver, standard UserDefaults, keyed by type id). App-internal.
- QuantityMetric enum: the 6 quantity types + canonical read units (HRV ms, HR/resp per-min, temp °C, mass kg, VO2 mL/kg·min).
- HealthKitReader: readQuantity()/readSleep() via HKAnchoredObjectQueryDescriptor.result(for:) — returns MetricDelta{samples, deletedUUIDs, newAnchor}. Does NOT persist the anchor; commit()/commitSleep() persist it ONLY after the caller confirms ingest (at-least-once; backend upsert dedupes).
- Deletions surfaced via deletedUUIDs (critical for RHR delete-and-replace).
- Sleep samples kept raw (all stages) for the assembler.
- ADR 0049. xcodebuild simulator BUILD SUCCEEDED. Real anchored reads are device-only → functionally verified when n1zt wires the POST.
