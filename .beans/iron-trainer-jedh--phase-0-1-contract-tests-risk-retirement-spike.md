---
# iron-trainer-jedh
title: 'Phase 0-1: contract tests + risk-retirement spike'
status: in-progress
type: epic
priority: normal
created_at: 2026-07-15T18:21:04Z
updated_at: 2026-07-15T19:39:30Z
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
- [ ] Contract-test extraction (IN PROGRESS): black-box httpx suite in backend/contract_tests/ runnable against any BASE_URL — Python now, Quarkus later
