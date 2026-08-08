---
# iron-trainer-bvif
title: Admin ingests API + view
status: todo
type: feature
priority: normal
created_at: 2026-08-08T21:58:33Z
updated_at: 2026-08-08T21:58:33Z
parent: iron-trainer-6uys
blocked_by:
    - iron-trainer-j05e
---

Admin surface for the ingest log.

## Todo
- [ ] GET /api/admin/health/ingests?days=N&athlete_id=&source=&ok= (@RequireAdmin): paginated recent ingests, newest first.
- [ ] Per-athlete 'last ingest per source' summary (last received_at + ok per hae/native).
- [ ] Frontend: an Ingests section/table in the admin Health tab (client, athlete, when, days/records, unknown/bad counts, ok pill, error).

## Notes
Mirror the existing admin health/jobs endpoint shape + guard. Reuse the failure-pill styling.
