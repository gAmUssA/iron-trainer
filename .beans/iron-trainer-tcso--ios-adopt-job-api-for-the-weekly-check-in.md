---
# iron-trainer-tcso
title: 'iOS: adopt job API for the Weekly Check-in'
status: completed
type: feature
priority: normal
created_at: 2026-07-13T04:36:47Z
updated_at: 2026-07-15T02:33:12Z
parent: iron-trainer-03qt
---

The iOS check-in held one HTTP request open for 90s — fragile on cellular / app backgrounding. Adopt PR #21's job API:
- [ ] PlanNetworkSource.checkin(): POST ?async=1 → poll GET /api/jobs/{id} (2s, 10min cap, transient tolerance, cancellation-cooperative)
- [ ] activeCheckinJobID() via /api/jobs/summary; TodayView resumes an in-flight check-in on appear (started on web or before app was killed)
- [ ] Sim verify: async round-trip in uvicorn log + story sheet; resume after simctl terminate/relaunch
- [x] PR #22 merged (incl. Copilot 4xx fail-fast fix); TestFlight 0.1.0 (202607131537) uploaded 2026-07-13

## Summary of Changes

iOS check-in now submits ?async=1 and polls the job API (2s/10min cap, transient tolerance, definitive-4xx fail-fast) with automatic resume of in-flight check-ins on TodayView appear. Sim-verified incl. kill-mid-job relaunch (30 polls over ~60s of live LLM work). Shipped in TestFlight 202607131537.
