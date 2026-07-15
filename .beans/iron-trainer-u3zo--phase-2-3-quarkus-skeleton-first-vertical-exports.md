---
# iron-trainer-u3zo
title: 'Phase 2-3: Quarkus skeleton + first vertical (exports)'
status: draft
type: epic
created_at: 2026-07-15T18:21:04Z
updated_at: 2026-07-15T18:21:04Z
parent: iron-trainer-37md
---

Quarkus app: REST resources, Hibernate entities for all tables, Flyway V1 baseline, smallrye-health, CI native build, dark deploy on Railway. First vertical = bearer-token auth + export endpoints (FIT/ZWO/ITW via com.garmin:fit) — byte-diffable, no session-cookie entanglement. Quarkus front door proxies unmigrated paths to FastAPI over the private network.
