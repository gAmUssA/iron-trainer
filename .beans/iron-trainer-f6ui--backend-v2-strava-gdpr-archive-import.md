---
# iron-trainer-f6ui
title: 'backend-v2: Strava GDPR archive import'
status: todo
type: feature
priority: low
created_at: 2026-07-16T23:53:05Z
updated_at: 2026-07-16T23:53:05Z
---

Port POST /api/strava/import + strava_import.parse_archive (parse a Strava GDPR export zip → activities → upsert → dedup → seed → rebuild). Multipart upload + zip parsing. Deferred from [[iron-trainer-3ptl]].
