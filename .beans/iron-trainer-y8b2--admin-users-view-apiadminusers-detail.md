---
# iron-trainer-y8b2
title: Admin Users view (/api/admin/users + detail)
status: in-progress
type: feature
priority: normal
created_at: 2026-08-08T10:29:35Z
updated_at: 2026-08-08T15:35:27Z
parent: iron-trainer-18n4
---

Cross-athlete user list + per-user detail for the admin console (behind the admin_session guard).

## Todo
- [x] GET /api/admin/users → all athletes: id, name, strava_athlete_id, apple linked?, connected?, ftp/thresholds set?, counts (activities, jobs, recent-failures).
- [x] GET /api/admin/users/{id} → detail: connected accounts (Strava/Apple), last-sync-per-kind, recent jobs, data counts (activities, daily_recovery days).
- [x] Frontend: Users table + a user-detail drawer/page (accounts, last sync, recent jobs).
## Notes
Athlete has no created_at column — 'joined' date not available (note or add later). Don't expose secrets/tokens. Blocked-by the foundation slice.
