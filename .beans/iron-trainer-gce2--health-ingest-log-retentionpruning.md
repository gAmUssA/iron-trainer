---
# iron-trainer-gce2
title: Health-ingest log retention/pruning
status: completed
type: task
priority: low
created_at: 2026-08-08T21:58:33Z
updated_at: 2026-08-09T12:46:04Z
parent: iron-trainer-6uys
blocked_by:
    - iron-trainer-j05e
---

Cap the health_ingest_log so it doesn't grow unbounded (many small rows per day per athlete). Options: a scheduled prune (delete rows older than N days) or a row cap per athlete. Ponytail: a simple daily 'delete where received_at < now-90d' is enough; revisit if volume warrants.

## Summary of Changes
HealthIngestLogRetention @Observes StartupEvent prunes health_ingest_log rows older than 90d (best-effort, mirrors JobRunner.onStart; no scheduler dependency). Testable prune(days) + HealthIngestLogRetentionTest. ADR 0060.
