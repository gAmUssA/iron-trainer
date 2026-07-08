---
# iron-trainer-fwb8
title: 'Phone-test feedback: workout detail back button + blank race widget'
status: in-progress
type: bug
priority: high
created_at: 2026-07-08T20:19:39Z
updated_at: 2026-07-08T20:42:13Z
---

From Viktor's on-device test of build 202607081335:
- [x] Workout detail is now a NavigationStack push (PlanRoute.workout) — back button verified in simulator, returns to Today view.
- [x] Widget fixes: adaptive .background container, no-data timeline .after(15m), Settings 'Widget data' diagnostic row. Device confirmation pending next TestFlight.
- [ ] Rebuild, sim-verify, PR (hold for merge), TestFlight after merge
