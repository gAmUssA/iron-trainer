---
# iron-trainer-f6ui
title: 'backend-v2: Strava GDPR archive import'
status: completed
type: feature
priority: low
created_at: 2026-07-16T23:53:05Z
updated_at: 2026-07-20T01:26:54Z
---

Port POST /api/strava/import + strava_import.parse_archive (parse a Strava GDPR export zip → activities → upsert → dedup → seed → rebuild). Multipart upload + zip parsing. Deferred from [[iron-trainer-3ptl]].

## Summary of Changes
Ported POST /api/strava/import + full parse_archive to backend-v2:
- StravaArchive.parse: activities.csv (RFC4180 CSV, non-ISO dates, utf-8-sig) + .fit (Garmin FIT SDK decode, session-summary precedence) + .gpx/.tcx (StAX, XXE-safe) + .gz, with 100MB bomb guard.
- StravaSync.runImport: upsert -> prune -> local dedup -> seed -> rebuild -> summary.
- StravaResource POST /import: multipart, ?async job (409 already-running), 413 >2GB, 400 non-export.
- max-body-size 2100M.
Tests: StravaArchiveTest (CSV/GPX/TCX/FIT), StravaImportEndpointTest, import-reject parity. v2 166 green. ADR 0039. Entire Strava surface now on backend-v2.
