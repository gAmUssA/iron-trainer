---
# iron-trainer-jedh
title: 'Phase 0-1: contract tests + risk-retirement spike'
status: completed
type: epic
priority: normal
created_at: 2026-07-15T18:21:04Z
updated_at: 2026-07-15T19:53:34Z
parent: iron-trainer-37md
---

Extract black-box contract tests from the pytest suite (in-process/monkeypatched today — cannot hit an external URL as-is; ~40-60% extractable, hardens the Python app regardless). Complete the spike: Hibernate/Panache + Flyway baseline on Dev-Services Postgres, FIT encode-decode round-trip vs Python output, virtual-thread job row, native build in GH Actions. LC4J structured-output item already DONE (spikes/lc4j-structured-output).

## Progress

- [x] Quarkus skeleton scaffolded (platform 3.37.3, Java 21): rest-jackson, hibernate-orm-panache, jdbc-postgresql, flyway, smallrye-health
- [x] Probe vertical GREEN: REST resource → Panache entity → Flyway V1 → Dev Services Postgres 18.4 (test passes; learned: pooled-lo sequence convention + explicit @Column for snake_case)
- [x] LC4J native json_schema proven (spikes/lc4j-structured-output)
- [x] V1 = real prod schema (supabase db dump, 12 tables) — PR #31
- [x] FIT interop: official Java SDK decodes Python files; power/HR conventions confirmed; EXPOSED probable ms-scale duration bug in Python export (bug bean iron-trainer-sqib)
- [x] Virtual-thread JobRunner against the real job table (queued→running→succeeded, per-transition transactions)
- [x] Native image job in backend-v2.yml (container build + binary smoke-run, gated on green tests)
- [x] Contract-test extraction: 11-test black-box httpx suite (backend/contract_tests/, run_local.sh, contract CI job) — PR #33

## Summary of Changes

Phase 0-1 complete across PRs #30-#33: Quarkus 3.37 skeleton (REST/Panache/Flyway/Dev Services), real prod schema baseline via supabase db dump, real Athlete entity, FIT interop with the official Java SDK (found Python duration-scale bug sqib), virtual-thread JobRunner on the shared job table, native image building + smoke-running in CI against Postgres, LC4J native json_schema proven, and the 11-test black-box contract harness that validates every future vertical against both implementations.
