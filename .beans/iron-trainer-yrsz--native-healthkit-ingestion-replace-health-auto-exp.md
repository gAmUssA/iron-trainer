---
# iron-trainer-yrsz
title: 'Native HealthKit ingestion: replace Health Auto Export with our iOS app'
status: todo
type: feature
created_at: 2026-07-15T03:59:41Z
updated_at: 2026-07-15T03:59:41Z
parent: iron-trainer-udbc
---

Iron Trainer's iOS app reads sleep (stages), HRV, resting HR (+ respiratory rate, wrist temp) directly from HealthKit and pushes to the existing POST /api/health/ingest protocol (or a cleaner native variant) — removing the third-party Health Auto Export dependency (Premium paywall, unguaranteed delivery, manual setup). Research in flight: HealthKit background delivery, observer/anchored queries, sleep-stage APIs, permission UX, delivery reliability vs HAE. Garmin→Apple Health→us remains the athlete-owned pipeline.
