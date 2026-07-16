---
# iron-trainer-rskm
title: 'backend-v2: port fitness-tests vertical (reads + writes)'
status: completed
type: feature
priority: high
created_at: 2026-07-16T16:29:40Z
updated_at: 2026-07-16T19:17:29Z
---

Port /api/tests: reads (GET '' catalog+due, GET /results, GET /{slug}/prefill) AND writes (POST /result save_test_result, POST /result/{id}/apply apply_test_result→thresholds, POST /{slug}/schedule add_test_workout_to_plan). Parity-gate all endpoints (write-parity: POST to both, compare response shapes modulo ids). Flip READS via PROXY_PATHS now; writes stay dormant until proxy POST + Phase 7 (see flip bean).



## Reads SHIPPED (this PR)
GET /api/tests (catalog+last_tested+due), GET /results, GET /{slug}/prefill — byte-parity verified. New: FitnessTests.java (catalog), FitnessTestResult entity+toRow, Activity entity. Writes (POST result/apply/schedule) = next PR (highest risk: apply cascades into recompute_tss+rebuild_metrics).



## Writes SHIPPED (record + schedule)
POST /api/tests/result (compute + save) + POST /{slug}/schedule (to_workout + add to active plan) — byte-parity verified. New: FitnessTests.compute + toWorkout; PlannedWorkout write cols. apply (cascade) SPLIT to the metrics-write vertical [[iron-trainer-adix]]; write traffic flip stays on [[iron-trainer-93o6]] (proxy POST + Phase 7).
