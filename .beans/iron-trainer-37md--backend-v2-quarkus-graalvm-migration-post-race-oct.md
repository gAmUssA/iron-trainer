---
# iron-trainer-37md
title: 'Backend v2: Quarkus + GraalVM migration (post-race, Oct–Dec 2026)'
status: draft
type: milestone
created_at: 2026-07-15T18:20:04Z
updated_at: 2026-07-15T18:20:04Z
---

Strangler migration of the FastAPI backend to Quarkus + GraalVM native on Railway. DO NOT START before the Sep 26 race. Plan: docs/research/quarkus-graalvm-migration.md (~21-40pd over 2-3 months). LangChain4j mandated for LLM (native json_schema PROVEN in spikes/lc4j-structured-output — capability flag mandatory). Postgres-only local dev via Dev Services; Flyway squash-baseline; both apps share one Postgres during the strangle.
