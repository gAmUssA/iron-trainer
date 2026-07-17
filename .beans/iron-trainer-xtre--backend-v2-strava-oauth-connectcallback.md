---
# iron-trainer-xtre
title: 'backend-v2: Strava OAuth connect/callback'
status: todo
type: feature
priority: normal
created_at: 2026-07-16T23:53:05Z
updated_at: 2026-07-16T23:53:05Z
---

Port GET /api/strava/connect (authorize_url redirect) + GET /api/strava/callback (exchange_code → save_tokens → seed_profile). Web/cookie surface + STRAVA_CLIENT_SECRET → needs the Phase 7 session-auth seam [[iron-trainer-eom4]] and secret handling. Deferred from [[iron-trainer-3ptl]].
