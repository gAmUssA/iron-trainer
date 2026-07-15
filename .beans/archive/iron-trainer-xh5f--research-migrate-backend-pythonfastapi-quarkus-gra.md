---
# iron-trainer-xh5f
title: 'Research: migrate backend Python/FastAPI → Quarkus + GraalVM'
status: completed
type: task
priority: normal
created_at: 2026-07-15T04:03:23Z
updated_at: 2026-07-15T18:20:24Z
parent: iron-trainer-37md
---

Evaluate a full backend rewrite: FastAPI+SQLModel/Alembic → Quarkus (REST, Hibernate/Panache, Flyway), GraalVM native image on Railway. Research agent in flight: component mapping, Claude SDK options in Java, FIT encoding (Garmin FIT SDK is Java-native), job system → virtual threads, SQLite-vs-Postgres story, native-image gotchas, migration strategy (strangler vs big-bang) with 194-test parity, and an honest is-it-worth-it.

## Summary of Changes

Research complete → docs/research/quarkus-graalvm-migration.md. Verdict: viable strangler migration, ~21-29pd, but NOT before the Sep 26 race; start Oct on the ~Sep 2026 LTS if desired. Weekend spike defined to retire all real risks (SQLite dialect, anthropic-java native, FIT round-trip, native build). All blockers of past years verified gone (official Anthropic Java SDK w/ structured outputs, official Garmin FIT on Maven Central).

**Decision (Viktor 2026-07-15): LangChain4j mandated for LLM integration** (quarkus-langchain4j-anthropic); official Java SDK is fallback only if structured output proves prompt-coaxed — verify schema enforcement in the spike.
