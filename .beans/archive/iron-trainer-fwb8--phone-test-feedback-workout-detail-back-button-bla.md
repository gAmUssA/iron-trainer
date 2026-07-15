---
# iron-trainer-fwb8
title: 'Phone-test feedback: workout detail back button + blank race widget'
status: completed
type: bug
priority: high
created_at: 2026-07-08T20:19:39Z
updated_at: 2026-07-15T02:33:12Z
parent: iron-trainer-03qt
---

From Viktor's on-device test of build 202607081335:
- [x] Workout detail is now a NavigationStack push (PlanRoute.workout) — back button verified in simulator, returns to Today view.
- [x] Widget fixes: adaptive .background container, no-data timeline .after(15m), Settings 'Widget data' diagnostic row. Device confirmation pending next TestFlight.
- [x] PR #15 merged (4aff92a); TestFlight build 0.1.0 (202607081816) uploaded 2026-07-08

## Summary of Changes

Workout detail converted to a NavigationStack push (PlanRoute.workout) — system back button restored; ImportModel.select removed. Widget hardening: adaptive .background container, .after(15m) no-data timeline policy, Settings 'Widget data' diagnostic row. Shipped in TestFlight 202607081816; device confirmation of the widget render is the remaining open question (tracked on bean ny45's on-device checklist).
