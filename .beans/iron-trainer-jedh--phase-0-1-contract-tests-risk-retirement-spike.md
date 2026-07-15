---
# iron-trainer-jedh
title: 'Phase 0-1: contract tests + risk-retirement spike'
status: in-progress
type: epic
priority: normal
created_at: 2026-07-15T18:21:04Z
updated_at: 2026-07-15T18:23:17Z
parent: iron-trainer-37md
---

Extract black-box contract tests from the pytest suite (in-process/monkeypatched today — cannot hit an external URL as-is; ~40-60% extractable, hardens the Python app regardless). Complete the spike: Hibernate/Panache + Flyway baseline on Dev-Services Postgres, FIT encode-decode round-trip vs Python output, virtual-thread job row, native build in GH Actions. LC4J structured-output item already DONE (spikes/lc4j-structured-output).
