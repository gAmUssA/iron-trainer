---
# iron-trainer-f6ui
title: 'backend-v2: Strava GDPR archive import'
status: completed
type: feature
priority: low
created_at: 2026-07-16T23:53:05Z
updated_at: 2026-07-20T03:53:24Z
---

Port POST /api/strava/import + strava_import.parse_archive (parse a Strava GDPR export zip → activities → upsert → dedup → seed → rebuild). Multipart upload + zip parsing. Deferred from [[iron-trainer-3ptl]].

## Merged
PR #79 merged. All 12 CI checks green (native + parity). Entire Strava surface (connect+callback+disconnect+sync+dedup+import) now on backend-v2.
