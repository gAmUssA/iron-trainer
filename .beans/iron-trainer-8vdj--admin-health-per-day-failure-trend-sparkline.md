---
# iron-trainer-8vdj
title: 'Admin health: per-day failure trend sparkline'
status: completed
type: feature
priority: low
created_at: 2026-08-08T19:54:50Z
updated_at: 2026-08-10T03:58:48Z
parent: iron-trainer-18n4
---

Add a per-day trend sparkline to the Health tab (is a kind's failure rate getting worse over the window). Deferred from j41l/ADR 0058: needs a second time-bucketed query (group by day). The 1/7/30d window switch is the current stand-in.

## Summary of Changes
GET /api/admin/health/jobs now returns a 'daily' array (per-day total/failed/failure_rate, group-by substring(created_at,1,10)+status, sparse). Frontend: 'Daily failure trend' MiniSpark on the Health tab. Test asserts today's bucket + out-of-window exclusion. ADR 0065. Completes admin epic 18n4 sync-health follow-ups.
