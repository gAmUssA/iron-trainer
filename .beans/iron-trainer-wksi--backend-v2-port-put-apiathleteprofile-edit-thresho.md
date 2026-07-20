---
# iron-trainer-wksi
title: 'backend-v2: port PUT /api/athlete/profile (edit thresholds → recompute TSS + refresh future plan targets)'
status: completed
type: feature
priority: high
created_at: 2026-07-20T16:03:35Z
updated_at: 2026-07-20T16:15:52Z
---

Port PUT /api/athlete/profile: validated ProfileUpdate (exclude_unset), save_profile, recompute_tss (all activities), rebuild_metrics, and refresh_future_plan_targets on target-field change. Phase-7 tail.

## Shipped
PUT /api/athlete/profile: validated ProfileUpdate (exclude_unset, null-clear, bounds→422 before auth), save_profile, recompute_tss (all activities), rebuild_metrics, refresh_future_plan_targets (new PlanTargets bean reusing plan machinery). Tests: ProfilePutTest + validation parity (mutating happy-path parity skipped — rebuild_metrics would wipe seeded metrics). v2 181 green. ADR 0042. Phase-7 backlog: 7 → 6.
