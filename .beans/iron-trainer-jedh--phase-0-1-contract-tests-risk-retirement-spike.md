---
# iron-trainer-jedh
title: 'Phase 0-1: contract tests + risk-retirement spike'
status: in-progress
type: epic
priority: normal
created_at: 2026-07-15T18:21:04Z
updated_at: 2026-07-15T18:38:57Z
parent: iron-trainer-37md
---

Extract black-box contract tests from the pytest suite (in-process/monkeypatched today — cannot hit an external URL as-is; ~40-60% extractable, hardens the Python app regardless). Complete the spike: Hibernate/Panache + Flyway baseline on Dev-Services Postgres, FIT encode-decode round-trip vs Python output, virtual-thread job row, native build in GH Actions. LC4J structured-output item already DONE (spikes/lc4j-structured-output).

## Progress

- [x] Quarkus skeleton scaffolded (platform 3.37.3, Java 21): rest-jackson, hibernate-orm-panache, jdbc-postgresql, flyway, smallrye-health
- [x] Probe vertical GREEN: REST resource → Panache entity → Flyway V1 → Dev Services Postgres 18.4 (test passes; learned: pooled-lo sequence convention + explicit @Column for snake_case)
- [x] LC4J native json_schema proven (spikes/lc4j-structured-output)
- [ ] Replace provisional V1 with pg_dump --schema-only baseline + baseline-on-migrate
- [ ] FIT encode-decode round-trip vs Python fit_export output (com.garmin:fit)
- [ ] Virtual-thread job writing a job row
- [ ] Native image build in GitHub Actions
- [ ] Contract-test extraction from pytest suite (black-box subset w/ --base-url)
