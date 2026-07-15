---
# iron-trainer-xh5f
title: 'Research: migrate backend Python/FastAPI → Quarkus + GraalVM'
status: in-progress
type: task
created_at: 2026-07-15T04:03:23Z
updated_at: 2026-07-15T04:03:23Z
---

Evaluate a full backend rewrite: FastAPI+SQLModel/Alembic → Quarkus (REST, Hibernate/Panache, Flyway), GraalVM native image on Railway. Research agent in flight: component mapping, Claude SDK options in Java, FIT encoding (Garmin FIT SDK is Java-native), job system → virtual threads, SQLite-vs-Postgres story, native-image gotchas, migration strategy (strangler vs big-bang) with 194-test parity, and an honest is-it-worth-it.
