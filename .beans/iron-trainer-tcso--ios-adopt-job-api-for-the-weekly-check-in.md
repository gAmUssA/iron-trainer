---
# iron-trainer-tcso
title: 'iOS: adopt job API for the Weekly Check-in'
status: in-progress
type: feature
created_at: 2026-07-13T04:36:47Z
updated_at: 2026-07-13T04:36:47Z
---

The iOS check-in held one HTTP request open for 90s — fragile on cellular / app backgrounding. Adopt PR #21's job API:
- [ ] PlanNetworkSource.checkin(): POST ?async=1 → poll GET /api/jobs/{id} (2s, 10min cap, transient tolerance, cancellation-cooperative)
- [ ] activeCheckinJobID() via /api/jobs/summary; TodayView resumes an in-flight check-in on appear (started on web or before app was killed)
- [ ] Sim verify: async round-trip in uvicorn log + story sheet; resume after simctl terminate/relaunch
- [ ] PR (hold for merge) → TestFlight after merge
