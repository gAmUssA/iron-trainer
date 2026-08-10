---
# iron-trainer-og06
title: 'Admin health: p50/p95 job durations per kind'
status: completed
type: feature
priority: low
created_at: 2026-08-08T19:54:50Z
updated_at: 2026-08-10T00:09:05Z
parent: iron-trainer-18n4
---

Add duration percentiles (p50/p95) per kind to /api/admin/health/jobs for latency triage. Deferred from j41l/ADR 0058: percentiles need loading + sorting the windowed rows (the counts use a cheap group-by). Compute finished_at - started_at per kind over the window.

## Summary of Changes
GET /api/admin/health/jobs now returns p50_ms/p95_ms/timed per kind (finished-started, Java nearest-rank percentile over the window's timed jobs; ponytail note for SQL percentile_cont at scale). Frontend: p50/p95 columns on the Health tab per-kind table. Tests: AdminHealthDurationTest (unit) + AdminHealthResourceTest.durationPercentilesPerKind. ADR 0064.
