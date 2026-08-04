---
# iron-trainer-48hy
title: WHOOP export ZIP upload — parse, store, overlay vs Strava/HealthKit
status: in-progress
type: feature
priority: normal
created_at: 2026-08-04T22:51:36Z
updated_at: 2026-08-04T23:08:58Z
parent: iron-trainer-ids6
---

Upload the WHOOP app data-export ZIP (physiological_cycles.csv, sleeps.csv, workouts.csv, journal_entries.csv), parse it server-side, store as distinct source='whoop_export', and render an overlay/comparison page vs existing Strava activities and HealthKit-derived readiness metrics.

Interim path for epic ids6: gets WHOOP Recovery % + Strain into the app TODAY without OAuth/app-approval (10-user cap doesn't apply to a file upload). API pull (4a6s) can later write into the same tables/source model.

## Todo
- [x] Flyway migration: whoop_cycles table keyed (athlete_id, date), upsert-safe re-upload (V4)
- [x] Quarkus multipart endpoint: POST /api/whoop/import (zip) → parse CSVs by header name (tolerant), upsert
- [x] GET /api/whoop/cycles?days=N (newest-first); import returns summary inline
- [x] React WHOOP tab: upload zip, import summary, 4 charts (Recovery %, HRV RMSSD-vs-SDNN, RHR overlay, Strain vs TSS)
- [ ] Validate with real export zip (user to provide — not currently on disk)
- [x] ADR 0053

## Notes (2026-08-05)

Built on worktree branch worktree-whoop-import. Backend mirrors StravaResource.importArchive/StravaArchive (csvDictRows/num made public + reused). Only physiological_cycles.csv is read (sleeps/workouts arrive via HealthKit — no double-count). Day = local wake date via Cycle timezone offset. Tests: WhoopArchiveTest (5) + WhoopResourceTest (2, end-to-end incl. idempotent re-upload); full suite 217 green. Readiness-vs-Recovery overlay deferred: readiness history is never persisted (see ADR consequences, bean v7dc). Remaining todo: validate with the real export zip (not found on disk — searched Downloads + mdfind).
