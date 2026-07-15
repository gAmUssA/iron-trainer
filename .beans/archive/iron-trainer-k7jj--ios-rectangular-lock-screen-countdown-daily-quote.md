---
# iron-trainer-k7jj
title: 'iOS: rectangular lock-screen countdown + daily quote, km/mi units, auto-scroll plan to today'
status: completed
type: feature
priority: normal
created_at: 2026-07-09T01:48:34Z
updated_at: 2026-07-15T02:33:12Z
parent: iron-trainer-03qt
---

Viktor's requests after confirming the Today release works on device:
- [x] accessoryRectangular countdown + daily quote (20 quotes, rotated by day-of-year; midnight timeline entry rolls it)
- [x] km/mi Settings picker; paces now m:ss /km|/mi (was raw s/km); verified in sim (9:28–10:18 /mi). kg/lbs deferred (no weight surface)
- [x] Plan list auto-scrolls to today (chronological-min target + deferred scrollTo; first attempt overshot, fixed + re-verified in sim)
- [x] Sim verified; PR #16 merged (6c83a44); TestFlight 0.1.0 (202607082231) uploaded 2026-07-08

## Summary of Changes

accessoryRectangular race-countdown widget with day-of-year-rotated quotes; km/mi display units (Settings picker, m:ss pace format, <800m stays meters, swim /100m); plan list auto-scrolls to today. Shipped in TestFlight 202607082231; device check of the rectangular widget + quote rollover with Viktor.
