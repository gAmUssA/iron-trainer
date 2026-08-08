---
# iron-trainer-j05e
title: Persist a health-ingest audit log (foundation)
status: completed
type: feature
priority: high
created_at: 2026-08-08T21:58:33Z
updated_at: 2026-08-08T23:06:30Z
parent: iron-trainer-6uys
---

Foundation: record every POST /api/health/ingest as an audit row so the rest of the epic has data to show.

## Todo
- [x] New table health_ingest_log (Flyway migration): id, athlete_id (nullable — unauth posts land days:0), client/source (hae|native|unknown), received_at (ISO), ok (bool), days_stored, records, unknown_metrics_count, bad_dates_count, byte_size, error (nullable, truncated), user_agent.
- [x] Write one row per ingest in HealthResource.ingest (success AND failure/ malformed-JSON paths), best-effort (never fail the ingest because logging failed).
- [x] Source detection: read a client marker header (native app sets e.g. X-Ingest-Client: native; document adding a matching custom header to the HAE automation) with User-Agent as a fallback heuristic; default 'unknown'.
- [x] Test: ingest writes a log row with the right counts; malformed JSON still logs ok=false.

## Notes
Keep the row small — do NOT store the full payload (PII + size). HAE batches large syncs into multiple POSTs, so each POST is its own row.

## Summary of Changes
V7 health_ingest_log table + HealthIngestLog entity; HealthResource.ingest writes a best-effort audit row on success AND malformed paths; detectSource (X-Ingest-Client header, UA fallback); native app tags X-Ingest-Client: native. Tests: HealthSourceDetectTest (unit), HealthIngestWriteTest. Shipped with bvif in one PR.
