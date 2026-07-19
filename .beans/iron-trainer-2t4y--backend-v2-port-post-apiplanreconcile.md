---
# iron-trainer-2t4y
title: 'backend-v2: port POST /api/plan/reconcile'
status: completed
type: feature
priority: normal
created_at: 2026-07-19T01:59:51Z
updated_at: 2026-07-19T04:41:07Z
---

Port service.reconcile: match actuals to planned (reconcile.match_workouts) → replan the next N future weeks (weeks_ahead 1-4) → compliance summary + form_flag. Response {matched, compliance, weeks_replanned, replanned, form_flag}. ValueError → 400. Reuses replan internals + Compliance (gu6p) + form_flag. Epic 9gjv / milestone 37md.

## Summary of Changes (ADR 0035)
Ported POST /api/plan/reconcile. Reconcile.matchWorkouts (activity↔planned-workout matcher: ±1 day, nearest-then-biggest-TSS, completed/skipped/upcoming; mutates entities in tx). PlanResource.reconcile composes match → replanOneWeek(next weeks_ahead) → Compliance.recent + form_flag. Response {matched, compliance, weeks_replanned, replanned, form_flag}.
- weeks_ahead 1-4 → 422 out of range; no plan → 400. Reuses replanOneWeek (extracted), Compliance (gu6p), formFlag, mondayOf.
- Tests: ReconcileTest (matchWorkouts fixed-date pins) + endpoint test (shape + 422) + Python parity (byte-for-byte response + 422 parity). v2 suite 140 green.
Last plan slice remaining: checkin (composite). Flip-eligible after deploy + parity-green.

## Code-review fixes (PR #75, full re-run after session-limit)
- #2 (CONFIRMED): Brick tie-break determinism — changed Python _MATCHES sets → ordered lists ([Bike,Run]) so both backends match deterministically on an exact day+TSS tie (was hash-randomized set iteration in Python). Unreachable by the template parity test (no Brick) but a genuine cross-backend fix.
- #4 (cleanup): compliance + form_flag now share ONE read transaction.
- #5 (cleanup): upcomingWeekStarts uses Params.sliceStop.
- #1 (accepted): FastAPI 422s a present-but-empty bool query (?use_llm=), but RESTEasy collapses it to null (== absent), so that exact edge isn't reproducible without raw query parsing; no real client sends empty bool values. Documented in Params.boolOr.
- #3 (deferred): compliance re-scans activities that matchWorkouts already loaded — infrequent endpoint; would need cross-tx refactor.
v2 suite 140 green.
