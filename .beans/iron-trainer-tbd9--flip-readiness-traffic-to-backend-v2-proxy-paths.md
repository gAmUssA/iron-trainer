---
# iron-trainer-tbd9
title: Flip readiness traffic to backend-v2 (PROXY_PATHS)
status: completed
type: task
priority: high
created_at: 2026-07-16T03:22:08Z
updated_at: 2026-07-16T04:34:02Z
---

Route live bearer /api/metrics/readiness/today traffic to the Quarkus native binary by adding it to PROXY_PATHS on the FastAPI Railway service (preserving the export paths). Prereqs done: readiness vertical (PR #45), config-driven proxy vqbi (PR #46), UTC pinning zk7z (PR #47). Verify backend-v2 is deployed with readiness, set the env, watch v2 access logs for readiness/today, confirm iOS calls succeed. Instant rollback = remove the path.

## Summary
Flipped 2026-07-16 04:31 UTC. Set PROXY_PATHS=/api/export/workout/*,/api/export/plan.itw,/api/metrics/readiness/today on the iron-trainer (FastAPI) service. Pre-checks: backend-v2 serves readiness (no-auth probe 401, health 200); commit b209daf deployed. FastAPI redeploy 6f8278d5 SUCCESS. CONFIRMED live: FastAPI strangler log 'Proxied to backend-v2: /api/metrics/readiness/today -> 200' on a real bearer call (04:33:07). Exports still proxying. Rollback = remove the readiness path from PROXY_PATHS.
