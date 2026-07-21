---
# iron-trainer-ghbo
title: 'backend-v2: build SPA in Docker (stop committing dist)'
status: todo
type: task
priority: deferred
created_at: 2026-07-17T04:45:21Z
updated_at: 2026-07-21T00:32:03Z
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

## Update 2026-07-20: Quinoa infeasible on Quarkus 3.37
No released quarkus-quinoa supports Quarkus 3.37 (it references the removed `HttpBuildTimeConfig`, renamed to `VertxHttpBuildTimeConfig`). Front door instead serves the SPA via static `META-INF/resources` + `SpaFallback` NotFoundException mapper (bean 7fso). This bean is now the follow-up: **stop committing the built dist** by moving the Railway backend-v2 root dir to repo-root and building the SPA in a Docker stage (frontend/ becomes reachable in the build context). Requires a Railway dashboard root-directory change + Dockerfile COPY-path rewrite + watchPatterns incl frontend/**.

## Implementation 2026-07-20 — Python-approach (repo-root context)
Viktor: 'why not the same approach as the Python backend?' — right, the FastAPI image built the SPA because its context was the repo root. frontend/ stays at root (no move).
- backend-v2/Dockerfile → repo-root context: node stage builds frontend/ → dist, injected into src/main/resources/META-INF/resources before the native build.
- backend-v2/railway.toml → dockerfilePath=backend-v2/Dockerfile, watchPatterns=[backend-v2/**, frontend/**].
- .dockerignore (new) keeps the repo-root context lean; committed dist removed + gitignored; build-frontend.sh now local-dev-only.
- Needs Railway service setting: Root Directory → repo root, Config File → backend-v2/railway.toml (via MCP after `railway login`).
- Local docker unreliable this session; COPY paths verified by inspection; native SPA bundling already proven in the deployed image; Railway deploy-check is the real verification (failed deploy keeps old image → front door safe).
