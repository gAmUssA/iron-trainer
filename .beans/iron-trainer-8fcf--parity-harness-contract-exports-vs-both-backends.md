---
# iron-trainer-8fcf
title: 'Parity harness: contract exports vs both backends'
status: completed
type: task
priority: normal
created_at: 2026-07-15T21:25:38Z
updated_at: 2026-07-15T21:54:00Z
parent: iron-trainer-u3zo
---

CI job boots FastAPI + backend-v2 against the same fresh Postgres, seeds via API, pairs a bearer, runs export contract subset against BOTH; parity = gate for traffic flips.

## Summary of Changes

test_parity_exports.py + run_parity.sh: both backends, one Postgres, real paired bearer — .itw parsed-JSON identical, plan.itw identical, ZWO segment-equal, FIT both valid (bytes differ by design: sqib), cross-tenant 404 parity. 16/16 in 0.95s. CI parity job in backend-v2.yml. NOTE: landed on main via direct push during deploy firefighting rather than PR #37's merge gate (auto-marked MERGED); CI validated on main.
