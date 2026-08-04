---
# iron-trainer-48hy
title: WHOOP export ZIP upload — parse, store, overlay vs Strava/HealthKit
status: completed
type: feature
priority: normal
created_at: 2026-08-04T22:51:36Z
updated_at: 2026-08-04T23:13:17Z
parent: iron-trainer-ids6
---

Upload the WHOOP app data-export ZIP (physiological_cycles.csv, sleeps.csv, workouts.csv, journal_entries.csv), parse it server-side, store as distinct source='whoop_export', and render an overlay/comparison page vs existing Strava activities and HealthKit-derived readiness metrics.

Interim path for epic ids6: gets WHOOP Recovery % + Strain into the app TODAY without OAuth/app-approval (10-user cap doesn't apply to a file upload). API pull (4a6s) can later write into the same tables/source model.

## Todo
- [x] Flyway migration: whoop_cycles table keyed (athlete_id, date), upsert-safe re-upload (V4)
- [x] Quarkus multipart endpoint: POST /api/whoop/import (zip) → parse CSVs by header name (tolerant), upsert
- [x] GET /api/whoop/cycles?days=N (newest-first); import returns summary inline
- [x] React WHOOP tab: upload zip, import summary, 4 charts (Recovery %, HRV RMSSD-vs-SDNN, RHR overlay, Strain vs TSS)
- [x] Validate with real export zip — 2213 cycles (2019-08-24 → 2026-07-29) imported end-to-end; headers matched exactly; UI verified with Playwright screenshot
- [x] ADR 0053

## Notes (2026-08-05)

Built on worktree branch worktree-whoop-import. Backend mirrors StravaResource.importArchive/StravaArchive (csvDictRows/num made public + reused). Only physiological_cycles.csv is read (sleeps/workouts arrive via HealthKit — no double-count). Day = local wake date via Cycle timezone offset. Tests: WhoopArchiveTest (5) + WhoopResourceTest (2, end-to-end incl. idempotent re-upload); full suite 217 green. Readiness-vs-Recovery overlay deferred: readiness history is never persisted (see ADR consequences, bean v7dc). Remaining todo: validate with the real export zip (not found on disk — searched Downloads + mdfind).

## Summary of Changes

Shipped WHOOP export-ZIP import end-to-end (ADR 0053, branch worktree-whoop-import):
- V4 whoop_cycles (PK athlete_id+date), WhoopCycle entity, WhoopArchive parser (reuses StravaArchive csvDictRows/num; header-tolerant; local-wake-date attribution via Cycle timezone offset)
- POST /api/whoop/import (multipart, sync, idempotent upsert via merge; sorted oldest-first so later cycle wins duplicate days) + GET /api/whoop/cycles
- WHOOP tab (WhoopView.tsx): upload card + Recovery %/7d-mean, HRV RMSSD-vs-SDNN overlay, RHR overlay, Strain-vs-TSS
- Tests: WhoopArchiveTest (5), WhoopResourceTest (2 e2e); full suite green
- Validated against the real my_whoop_data_2026_07_29.zip: 2213 days, values spot-checked plausible; found+fixed reversed first/last summary (export is newest-first)

Deferred: Recovery-vs-Readiness overlay needs persisted readiness history (bean v7dc); workouts.csv ignored by design (HealthKit double-count).
