---
# iron-trainer-u3zo
title: 'Phase 2-3: Quarkus skeleton + first vertical (exports)'
status: completed
type: epic
priority: normal
created_at: 2026-07-15T18:21:04Z
updated_at: 2026-07-15T22:28:46Z
parent: iron-trainer-37md
---

Quarkus app: REST resources, Hibernate entities for all tables, Flyway V1 baseline, smallrye-health, CI native build, dark deploy on Railway. First vertical = bearer-token auth + export endpoints (FIT/ZWO/ITW via com.garmin:fit) — byte-diffable, no session-cookie entanglement. Quarkus front door proxies unmigrated paths to FastAPI over the private network.

## Dark deploy LIVE (2026-07-15)

backend-v2 on Railway: Quarkus 3.37.3 JVM, 1.755s start, prod profile, DB health UP against the shared Supabase Postgres, unauth /api/export/* → 401 (AUTH_REQUIRED=true, no default-athlete fallback). Domain: backend-v2-production-853d.up.railway.app. Gotcha log: interactive railway add auto-connected the repo and deployed the PYTHON app first (harmless idempotent alembic); fixed via source disconnect + tarball deploy from a clean dir. Remaining: strangler routing flip for /api/export/* (routing-mechanism decision pending) + repo-connected deploys (root-directory needs MCP re-auth or dashboard).

## Native in production (2026-07-15)

backend-v2 now runs the GRAALVM NATIVE binary on Railway: started in 0.276s (vs 1.755s JVM), health UP, unauth 401, /q/health healthcheck RE-ENABLED and passing (probe visible in the new access log). Root cause of the healthcheck saga: Railway Express/Railpack auto-builder was hijacking repo builds (ignoring railway.toml builder=DOCKERFILE) and producing containers without start.sh env derivation — forced via RAILWAY_DOCKERFILE_PATH=Dockerfile service variable. Native-image gotcha fixed en route: @ConfigProperty on a JAX-RS @Provider bakes build-time values (auth-required true-vs-false crash) → runtime ConfigProvider lookup. Observability added: HTTP access log (status+duration), auth-rejection events (never credentials), per-export INFO logs. 'Wait for CI' gating: dashboard-only setting — Viktor: tick it in backend-v2 service settings.

## FIRST PRODUCTION TRAFFIC (2026-07-15 22:26 UTC)

Viktor's iOS plan fetch served by the native Quarkus binary: backend-v2 log shows 'Export plan.itw: athlete=2 workouts=72 plan=7' + access-log 200 (81KB), FastAPI log shows 'Export proxied to backend-v2 -> 200'. Exports vertical COMPLETE end to end: endpoints → parity gate → dark deploy → native binary → live traffic with fallback + kill-switch. Epic closed.
