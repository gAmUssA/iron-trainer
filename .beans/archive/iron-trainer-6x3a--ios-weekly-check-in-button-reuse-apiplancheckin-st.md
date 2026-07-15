---
# iron-trainer-6x3a
title: 'iOS: Weekly Check-in button (reuse /api/plan/checkin story)'
status: completed
type: feature
priority: low
created_at: 2026-07-09T18:39:02Z
updated_at: 2026-07-15T02:33:12Z
parent: iron-trainer-03qt
---

Follow-up from iron-trainer-y6ss: a check-in button in the iOS Today view calling POST /api/plan/checkin and rendering the same story[] lines; refresh plan + widget snapshot after.

Implemented: Weekly Check-in row on the Today view (signed-in only) → POST /api/plan/checkin (90s timeout for LLM replans) → story sheet (same server-built lines as web) → refreshPlanQuietly() re-fetches plan + rewrites widget snapshot WITHOUT flipping through .loading (keeps the view + sheet mounted). Verified end-to-end in simulator incl. live LLM replan. PR pending merge; TestFlight after.

## Summary of Changes

Weekly Check-in row on the iOS Today view: POST /api/plan/checkin (90s timeout), story sheet with the server-built narrative, refreshPlanQuietly() keeps the view mounted while re-fetching the plan + rewriting the widget snapshot. Copilot's gating + @MainActor comments addressed. PR #20 merged; TestFlight 0.1.0 (202607092019) uploaded 2026-07-09. Sim-verified end-to-end incl. live LLM replan; device check with Viktor.
