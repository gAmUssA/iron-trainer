---
# iron-trainer-vcf4
title: Silent-sync / stale-ingest detection
status: completed
type: feature
priority: low
created_at: 2026-08-08T21:58:33Z
updated_at: 2026-08-09T12:46:04Z
parent: iron-trainer-6uys
blocked_by:
    - iron-trainer-j05e
---

Flag athletes whose last successful health ingest is older than a threshold (HAE silently stops under iOS background limits — a common, invisible failure). Surface 'last ingest N days ago' warnings in the admin Users/Health view; optionally a dashboard count of 'stale' athletes. No push/email yet — visibility first.

## Summary of Changes
Frontend-only: the Ingests 'last per client' table derives per-row status (failing if last ingest errored, 'stale Nd' if older than STALE_DAYS=3, else ok) + a header count of clients needing attention. Reuses last_by_source (no new endpoint). ADR 0060.
