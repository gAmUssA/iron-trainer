---
# iron-trainer-og06
title: 'Admin health: p50/p95 job durations per kind'
status: todo
type: feature
priority: low
created_at: 2026-08-08T19:54:50Z
updated_at: 2026-08-08T19:54:50Z
parent: iron-trainer-18n4
---

Add duration percentiles (p50/p95) per kind to /api/admin/health/jobs for latency triage. Deferred from j41l/ADR 0058: percentiles need loading + sorting the windowed rows (the counts use a cheap group-by). Compute finished_at - started_at per kind over the window.
