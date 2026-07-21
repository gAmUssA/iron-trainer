---
# iron-trainer-yrsz
title: 'Native HealthKit ingestion: replace Health Auto Export with our iOS app'
status: todo
type: feature
priority: normal
created_at: 2026-07-15T03:59:41Z
updated_at: 2026-07-21T15:41:53Z
parent: iron-trainer-2f2c
---

Iron Trainer's iOS app reads sleep (stages), HRV, resting HR (+ respiratory rate, wrist temp) directly from HealthKit and pushes to the existing POST /api/health/ingest protocol (or a cleaner native variant) — removing the third-party Health Auto Export dependency (Premium paywall, unguaranteed delivery, manual setup). Research in flight: HealthKit background delivery, observer/anchored queries, sleep-stage APIs, permission UX, delivery reliability vs HAE. Garmin→Apple Health→us remains the athlete-owned pipeline.

## Research complete (2026-07-15)

Full report: docs/research/native-healthkit-ingestion.md. Verdict: build it (~500-800 LOC Swift, zero backend changes — same HAE-shaped payload to /api/health/ingest). Key facts: sleep stage enum verified; HRV is spot-samples → sleep-window mean; RHR gets delete-and-replaced → handle HKDeletedObjects; night assembly must never merge stages across sources; observer+anchored queries with anchors advanced only after server ack; foreground catch-up beats HAE reliability alone. Gotchas: Garmin does NOT write HRV to Health (Apple Watch only — no regression vs HAE); wrist temp Apple-only; workouts v2 blocked on missing GPS/power from Garmin. Privacy policy required for review.
