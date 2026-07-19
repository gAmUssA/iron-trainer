---
# iron-trainer-ekyf
title: 'backend-v2: port POST /api/plan/checkin (weekly check-in)'
status: completed
type: feature
priority: normal
created_at: 2026-07-19T13:05:22Z
updated_at: 2026-07-19T13:32:57Z
---

Final plan slice. Port service.weekly_checkin: Strava sync (best-effort) → match actuals → reconcile/replan next week → readiness (feel-vs-data) → fitness-tests-due → key sessions → narrative story. Composes reconcile (2t4y), readiness, fitness-tests, strava sync. Async via ?async=1 (checkin job, s6v3). iOS check-in calls this. Some parts (Strava sync) not parity-testable. Epic 9gjv / milestone 37md — completes the plan vertical.

## Summary of Changes (ADR 0036) — COMPLETES THE PLAN VERTICAL
Ported POST /api/plan/checkin (the composite one-tap loop). Checkin entity+save (checkin table), Checkins pure helpers (sanitizeFeel + feelVsDataLine byte-parity strings), PlanResource.checkin/weeklyCheckin orchestration + all story strings, ?async=1 checkin job.
- Best-effort Strava sync (409→not-connected, else→failed); feel threaded into replan LLM context; today=UTC; @Consumes for optional body.
- Reused Readiness.compute/storyLine, FitnessTests.catalog/RETEST_DAYS, StravaSync.runSync, reconcileInternal (extracted), Compliance.
- Tests: CheckinsTest (clamp + feel-vs-data strings) + @QuarkusTest (full loop + no-body + async) + Python parity (converged fields + story minus sync/replan lines). v2 suite 148 green.
All 6 plan endpoints now on backend-v2. Plan writes flip-eligible (idempotency in place).

## Code-review fixes (PR #76)
- #1 (CONFIRMED): non-dict inputs (e.g. {"inputs":"3"}) → 422 (FastAPI CheckinBody parity), was silently accepted + persisted.
- #2 (CONFIRMED): Py.f0signed now preserves '-0' for values in (-0.5,0) matching Python f'{x:+.0f}' (fixes shared util — closes beans pp3s/t4md).
- #3 (CONFIRMED): sanitizeFeel accepts boolean feel values (Python bool is int → 1).
- #4 (CONFIRMED): note truncated by CODE POINT ([:280]) not UTF-16 char — no surrogate split.
- #5 (CONFIRMED): key-sessions null title renders 'None' (Python str(None)), was 'null'.
- Deferred (cleanup): afterH/keySessions double-scan (small plan; infrequent).
v2 suite 150 green.
