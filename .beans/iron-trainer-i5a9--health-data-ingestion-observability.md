---
# iron-trainer-i5a9
title: Health-data ingestion observability
status: todo
type: milestone
priority: normal
created_at: 2026-08-08T21:58:33Z
updated_at: 2026-08-08T21:58:33Z
---

Make Apple Health data ingestion (Health Auto Export + the native iOS HealthKit sync) observable from the admin console. Today POST /api/health/ingest is fire-and-forget: it upserts daily_recovery and returns counts, but persists NO record of the ingest event, and it is not a Job — so the admin console (epic 18n4) can't show whether/when health data arrived, from which client, or what was dropped. Research (2026-08-08): both HAE and the native app POST the SAME endpoint with no source marker; HAE supports custom headers (so a source tag is feasible) and can silently stop syncing under iOS background limits (stale-detection matters); HAE batches large payloads (audit must be per-request).
