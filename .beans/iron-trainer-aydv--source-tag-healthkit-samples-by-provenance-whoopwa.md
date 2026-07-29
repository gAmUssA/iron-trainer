---
# iron-trainer-aydv
title: Source-tag HealthKit samples by provenance (WHOOP/Watch/Oura)
status: todo
type: task
priority: normal
created_at: 2026-07-29T20:38:25Z
updated_at: 2026-07-29T20:38:25Z
parent: iron-trainer-ids6
---

Foundation for no-double-count + clean overlay. Our HealthKit ingestion should record each sample's SOURCE so WHOOP-via-HealthKit, Apple Watch, and Oura stay separable and readiness picks ONE source per metric instead of averaging duplicates.

## Todo
- [ ] On the iOS HealthKit read, capture sample.sourceRevision.source .name + .bundleIdentifier per sample; detect WHOOP by source.name=='WHOOP' (read the bundle id at RUNTIME — do not hardcode).
- [ ] Carry provenance into the ingest payload + store a source column on the recovery/metric rows.
- [ ] Readiness: choose a single source per metric (or expose per-source), never blend duplicates.
- [ ] Note: HealthKit HRV=SDNN vs WHOOP API HRV=RMSSD — never merge across those.
Check whether the current pipeline already carries any source attribution first.
