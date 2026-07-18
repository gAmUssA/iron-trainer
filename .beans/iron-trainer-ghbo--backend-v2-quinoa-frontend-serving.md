---
# iron-trainer-ghbo
title: 'backend-v2: Quinoa frontend serving'
status: todo
type: task
priority: deferred
created_at: 2026-07-17T04:45:21Z
updated_at: 2026-07-18T03:28:22Z
parent: iron-trainer-eom4
blocked_by:
    - iron-trainer-foi1
---

Serve the React frontend from backend-v2 via Quarkus Quinoa (replacing FastAPI static serving).

## Scoping + decision (2026-07-17)

Folded into cutover (foi1) — build SPA-serving there, not before.

**Why deferred:** frontend calls `/api` same-origin, so a backend-v2-served SPA
only does anything once backend-v2 is the front door (= foi1). Until then FastAPI
serves the SPA + is the front door; Quinoa on backend-v2 would be dead capability.

**Two structural blockers to resolve at cutover:**
1. backend-v2 Docker build context is `backend-v2/` → sibling `frontend/` is unreachable.
2. Mandrel native builder image has no Node (can't run the Vite build).

**Deployable approach (for foi1):** change the backend-v2 Railway service root dir to
repo root; multi-stage Dockerfile (Node stage builds frontend/dist → Mandrel native
bundles it via Quinoa `ui-dir` or META-INF/resources → serve SPA + fallback);
watchPatterns → backend-v2/** + frontend/**. Config: `quarkus.quinoa.ui-dir=../frontend`,
`enable-spa-routing=true`. Not parity-testable — verify by curling backend-v2 `/` for
index.html + SPA fallback post-deploy.
