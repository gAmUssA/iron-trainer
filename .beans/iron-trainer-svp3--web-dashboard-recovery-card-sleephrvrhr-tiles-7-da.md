---
# iron-trainer-svp3
title: 'Web dashboard: Recovery card (sleep/HRV/RHR tiles + 7-day sparklines)'
status: in-progress
type: feature
priority: normal
created_at: 2026-07-15T03:53:25Z
updated_at: 2026-07-15T03:58:20Z
parent: iron-trainer-udbc
---

Surface the ingested recovery data: last night's sleep (deep/REM split), HRV, RHR as stat tiles with 7-day sparklines and baseline deltas, next to the TodayCall banner. Data: GET /api/health/recovery.

## Summary of Changes

RecoveryCard on the dashboard (rendered only when data exists): sleep (deep/REM split), HRV, RHR stat tiles with 7-day sparklines and baseline deltas colored by direction-of-good. Also fixed readiness narrative: when a recovery flag downgrades green→easy, the veto now LEADS the reasons (banner no longer says GO EASY next to 'prime day for a key session'). Verified via seeded demo (9 ingested days, declining trends).
