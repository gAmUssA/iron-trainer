---
# iron-trainer-rskm
title: 'backend-v2: port fitness-tests vertical (reads + writes)'
status: todo
type: feature
priority: high
created_at: 2026-07-16T16:29:40Z
updated_at: 2026-07-16T16:29:40Z
---

Port /api/tests: reads (GET '' catalog+due, GET /results, GET /{slug}/prefill) AND writes (POST /result save_test_result, POST /result/{id}/apply apply_test_result→thresholds, POST /{slug}/schedule add_test_workout_to_plan). Parity-gate all endpoints (write-parity: POST to both, compare response shapes modulo ids). Flip READS via PROXY_PATHS now; writes stay dormant until proxy POST + Phase 7 (see flip bean).
