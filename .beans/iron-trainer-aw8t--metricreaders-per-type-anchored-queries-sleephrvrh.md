---
# iron-trainer-aw8t
title: MetricReaders — per-type anchored queries (sleep/HRV/RHR/resp/temp/mass)
status: todo
type: task
created_at: 2026-07-21T15:41:54Z
updated_at: 2026-07-21T15:41:54Z
parent: iron-trainer-yrsz
---

One anchored HKAnchoredObjectQuery per type with a persisted HKQueryAnchor. Types: sleepAnalysis (allAsleepValues), heartRateVariabilitySDNN (spot samples → sleep-window mean), restingHeartRate (process HKDeletedObjects, recompute yesterday — Apple delete-and-replaces estimates), respiratoryRate, appleSleepingWristTemperature (read-only, Watch-only), bodyMass, vo2Max. Advance anchor ONLY after server confirms (at-least-once; backend upsert idempotent).
