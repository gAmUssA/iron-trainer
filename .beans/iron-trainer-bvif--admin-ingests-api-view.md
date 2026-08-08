---
# iron-trainer-bvif
title: Admin ingests API + view
status: completed
type: feature
priority: normal
created_at: 2026-08-08T21:58:33Z
updated_at: 2026-08-08T23:06:30Z
parent: iron-trainer-6uys
blocked_by:
    - iron-trainer-j05e
---

Admin surface for the ingest log.

## Todo
- [x] GET /api/admin/health/ingests?days=N&athlete_id=&source=&ok= (@RequireAdmin): paginated recent ingests, newest first.
- [x] Per-athlete 'last ingest per source' summary (last received_at + ok per hae/native).
- [x] Frontend: an Ingests section/table in the admin Health tab (client, athlete, when, days/records, unknown/bad counts, ok pill, error).

## Notes
Mirror the existing admin health/jobs endpoint shape + guard. Reuse the failure-pill styling.

## Summary of Changes
GET /api/admin/health/ingests (@RequireAdmin): windowed/filterable/paginated feed + last_by_source (last per athlete+source, all-time). Frontend: dedicated Ingests admin tab (last-per-client + recent-ingests tables, source/ok/window filters). AdminIngestsResourceTest. NOTE: built as a separate Ingests tab, not a section inside Health — see ADR 0059.
