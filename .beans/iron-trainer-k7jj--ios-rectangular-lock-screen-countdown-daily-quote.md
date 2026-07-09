---
# iron-trainer-k7jj
title: 'iOS: rectangular lock-screen countdown + daily quote, km/mi units, auto-scroll plan to today'
status: in-progress
type: feature
priority: normal
created_at: 2026-07-09T01:48:34Z
updated_at: 2026-07-09T02:16:42Z
---

Viktor's requests after confirming the Today release works on device:
- [x] accessoryRectangular countdown + daily quote (20 quotes, rotated by day-of-year; midnight timeline entry rolls it)
- [x] km/mi Settings picker; paces now m:ss /km|/mi (was raw s/km); verified in sim (9:28–10:18 /mi). kg/lbs deferred (no weight surface)
- [x] Plan list auto-scrolls to today (chronological-min target + deferred scrollTo; first attempt overshot, fixed + re-verified in sim)
- [x] Sim verified; PR #16 merged (6c83a44). TestFlight cut pending Viktor's go
