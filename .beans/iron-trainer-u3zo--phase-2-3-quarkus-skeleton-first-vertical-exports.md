---
# iron-trainer-u3zo
title: 'Phase 2-3: Quarkus skeleton + first vertical (exports)'
status: in-progress
type: epic
priority: normal
created_at: 2026-07-15T18:21:04Z
updated_at: 2026-07-15T20:18:37Z
parent: iron-trainer-37md
---

Quarkus app: REST resources, Hibernate entities for all tables, Flyway V1 baseline, smallrye-health, CI native build, dark deploy on Railway. First vertical = bearer-token auth + export endpoints (FIT/ZWO/ITW via com.garmin:fit) — byte-diffable, no session-cookie entanglement. Quarkus front door proxies unmigrated paths to FastAPI over the private network.

## Progress

- [x] Bearer auth: BearerAuthFilter (SHA-256 → shared device_token table) + CurrentAthlete @RequestScoped tenancy (the CDI ContextVar replacement); local no-auth mode falls back to athlete 1 like FastAPI
- [x] Entities: DeviceToken, PlannedWorkout (read side)
- [x] GET /api/export/workout/{id}.itw — port of itw_export.py (schema_version 1, embedded thresholds, steps passthrough); cross-tenant 404 tested
- [x] .fit + .zwo endpoints: ZWO FTP-fraction port verified; FIT via official SDK with SPEC-CORRECT ms durations (deliberate divergence from Python sqib bug, decode-back tested)
- [ ] plan.itw + plan.zip (needs Plan entity)
- [ ] Railway dark deploy + strangler routing for /api/export/*
