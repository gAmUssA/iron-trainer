---
# iron-trainer-u3zo
title: 'Phase 2-3: Quarkus skeleton + first vertical (exports)'
status: draft
type: epic
priority: normal
created_at: 2026-07-15T18:21:04Z
updated_at: 2026-07-15T21:16:48Z
parent: iron-trainer-37md
---

Quarkus app: REST resources, Hibernate entities for all tables, Flyway V1 baseline, smallrye-health, CI native build, dark deploy on Railway. First vertical = bearer-token auth + export endpoints (FIT/ZWO/ITW via com.garmin:fit) — byte-diffable, no session-cookie entanglement. Quarkus front door proxies unmigrated paths to FastAPI over the private network.

## Dark deploy LIVE (2026-07-15)

backend-v2 on Railway: Quarkus 3.37.3 JVM, 1.755s start, prod profile, DB health UP against the shared Supabase Postgres, unauth /api/export/* → 401 (AUTH_REQUIRED=true, no default-athlete fallback). Domain: backend-v2-production-853d.up.railway.app. Gotcha log: interactive railway add auto-connected the repo and deployed the PYTHON app first (harmless idempotent alembic); fixed via source disconnect + tarball deploy from a clean dir. Remaining: strangler routing flip for /api/export/* (routing-mechanism decision pending) + repo-connected deploys (root-directory needs MCP re-auth or dashboard).
