---
# iron-trainer-gua2
title: Replace hand-built LinkedHashMap DTOs with Jackson records
status: todo
type: feature
priority: low
created_at: 2026-08-08T23:24:43Z
updated_at: 2026-08-08T23:24:43Z
parent: iron-trainer-y2yz
---

Dozens of responses/stored dicts are hand-assembled LinkedHashMaps with snake_case keys 'to match FastAPI model_dump() key set + order' (HealthResource:82-105, JobRunner.jobDict:151-163, Dashboards, Insights, RaceReadiness, Nutrition, Compliance, PlanResource, FitnessTests…). Key ORDER is insignificant to JSON parsers, but the snake_case KEYS and null-inclusion ARE the live iOS/web contract.

## Approach (DEFER — do opportunistically per vertical, never big-bang)
- [ ] Jackson records/DTOs with SNAKE_CASE naming strategy + @JsonInclude(ALWAYS) to preserve the nulls the maps emit. @JsonPropertyOrder only if diff-stability wanted.
- [ ] Per-endpoint, with response snapshot tests — the failure mode is silently dropping/renaming a field or flipping null-inclusion, breaking the iOS decoder.

## Notes
MEDIUM risk (iOS wire shape), L effort. Do one vertical at a time as it's already being touched.
