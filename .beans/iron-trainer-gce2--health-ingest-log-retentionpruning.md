---
# iron-trainer-gce2
title: Health-ingest log retention/pruning
status: todo
type: task
priority: low
created_at: 2026-08-08T21:58:33Z
updated_at: 2026-08-08T21:58:33Z
parent: iron-trainer-6uys
blocked_by:
    - iron-trainer-j05e
---

Cap the health_ingest_log so it doesn't grow unbounded (many small rows per day per athlete). Options: a scheduled prune (delete rows older than N days) or a row cap per athlete. Ponytail: a simple daily 'delete where received_at < now-90d' is enough; revisit if volume warrants.
