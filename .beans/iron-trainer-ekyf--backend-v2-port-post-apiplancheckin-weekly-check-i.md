---
# iron-trainer-ekyf
title: 'backend-v2: port POST /api/plan/checkin (weekly check-in)'
status: completed
type: feature
priority: normal
created_at: 2026-07-19T13:05:22Z
updated_at: 2026-07-19T13:19:15Z
---

Final plan slice. Port service.weekly_checkin: Strava sync (best-effort) → match actuals → reconcile/replan next week → readiness (feel-vs-data) → fitness-tests-due → key sessions → narrative story. Composes reconcile (2t4y), readiness, fitness-tests, strava sync. Async via ?async=1 (checkin job, s6v3). iOS check-in calls this. Some parts (Strava sync) not parity-testable. Epic 9gjv / milestone 37md — completes the plan vertical.

## Summary of Changes (ADR 0036) — COMPLETES THE PLAN VERTICAL
Ported POST /api/plan/checkin (the composite one-tap loop). Checkin entity+save (checkin table), Checkins pure helpers (sanitizeFeel + feelVsDataLine byte-parity strings), PlanResource.checkin/weeklyCheckin orchestration + all story strings, ?async=1 checkin job.
- Best-effort Strava sync (409→not-connected, else→failed); feel threaded into replan LLM context; today=UTC; @Consumes for optional body.
- Reused Readiness.compute/storyLine, FitnessTests.catalog/RETEST_DAYS, StravaSync.runSync, reconcileInternal (extracted), Compliance.
- Tests: CheckinsTest (clamp + feel-vs-data strings) + @QuarkusTest (full loop + no-body + async) + Python parity (converged fields + story minus sync/replan lines). v2 suite 148 green.
All 6 plan endpoints now on backend-v2. Plan writes flip-eligible (idempotency in place).
