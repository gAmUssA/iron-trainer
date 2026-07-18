---
# iron-trainer-gu6p
title: 'backend-v2: port GET /api/plan/compliance'
status: completed
type: feature
priority: normal
created_at: 2026-07-18T22:17:46Z
updated_at: 2026-07-18T22:24:40Z
---

Second plan slice. Deterministic read: {weeks, recent} from reconcile.compliance_by_week(plan_id) + recent_compliance(plan_id) — planned-vs-actual per week + recent-window summary over PlannedWorkout + Activity. No LLM. Parity-testable. Part of plan vertical (after qq5f GET /api/plan). Epic 9gjv / milestone 37md.

## Summary of Changes (ADR 0032)
Ported GET /api/plan/compliance to backend-v2. Compliance.byWeek + Compliance.recent (faithful ports of reconcile.compliance_by_week/recent_compliance): planned-vs-actual per ISO week + 21d recent window; non-dup activities, Py.round/roundInt banker's, Iso.parseDate, LocalDate.now() today. Java @QuarkusTest (per-status math) + Python test_plan_compliance_parity. v2 suite 127 green. Flip-eligible once deployed + parity-green.
