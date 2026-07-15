---
# iron-trainer-4lcx
title: 'Metrics/PMC vertical: /api/metrics/pmc + MetricDaily in backend-v2'
status: completed
type: task
priority: normal
created_at: 2026-07-15T23:37:27Z
updated_at: 2026-07-15T23:56:35Z
parent: iron-trainer-eg0j
---

Port /api/metrics/pmc?days=N: MetricDaily entity (metrics_daily table), windowed read (default 180, 0=all, inclusive-of-today cutoff), {days, window_days, total_days} response. Parity vs FastAPI; reuses the existing test_pmc_windowing contract. Establishes MetricDaily for the readiness vertical next.

## Summary of Changes

PR #43 merged. MetricDaily entity (composite PK) + /api/metrics/pmc?days=N with QUERY-LEVEL windowing (count for total + date>=cutoff list — Copilot review, better than FastAPI's load-all). 4 unit + PMC parity (18/18). Establishes MetricDaily for readiness next.
