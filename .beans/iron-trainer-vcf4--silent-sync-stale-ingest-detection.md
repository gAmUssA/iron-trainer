---
# iron-trainer-vcf4
title: Silent-sync / stale-ingest detection
status: completed
type: feature
priority: low
created_at: 2026-08-08T21:58:33Z
updated_at: 2026-08-09T15:41:37Z
parent: iron-trainer-6uys
blocked_by:
    - iron-trainer-j05e
---

Flag clients whose health ingest has gone quiet or is failing (HAE silently stops under iOS background limits — a common, invisible failure). No push/email yet — visibility first.

REVISED (ADR 0060): implemented in the admin **Ingests** tab (not Users/Health), keyed on the **last ingest attempt** per (athlete, source) rather than the last *successful* one — so a client whose recent syncs are all failing shows as 'failing' instead of silently 'ok'. Threshold STALE_DAYS=3.

## Summary of Changes
Frontend-only: the Ingests 'last per client' table derives per-row status (failing if last ingest errored, 'stale Nd' if older than STALE_DAYS=3, else ok) + a header count of clients needing attention. Reuses last_by_source (no new endpoint). ADR 0060.
