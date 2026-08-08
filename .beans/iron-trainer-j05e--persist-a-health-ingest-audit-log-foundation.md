---
# iron-trainer-j05e
title: Persist a health-ingest audit log (foundation)
status: in-progress
type: feature
priority: high
created_at: 2026-08-08T21:58:33Z
updated_at: 2026-08-08T22:57:05Z
parent: iron-trainer-6uys
---

Foundation: record every POST /api/health/ingest as an audit row so the rest of the epic has data to show.

## Todo
- [ ] New table health_ingest_log (Flyway migration): id, athlete_id (nullable — unauth posts land days:0), client/source (hae|native|unknown), received_at (ISO), ok (bool), days_stored, records, unknown_metrics_count, bad_dates_count, byte_size, error (nullable, truncated), user_agent.
- [ ] Write one row per ingest in HealthResource.ingest (success AND failure/ malformed-JSON paths), best-effort (never fail the ingest because logging failed).
- [ ] Source detection: read a client marker header (native app sets e.g. X-Ingest-Client: native; document adding a matching custom header to the HAE automation) with User-Agent as a fallback heuristic; default 'unknown'.
- [ ] Test: ingest writes a log row with the right counts; malformed JSON still logs ok=false.

## Notes
Keep the row small — do NOT store the full payload (PII + size). HAE batches large syncs into multiple POSTs, so each POST is its own row.
