---
# iron-trainer-4lcx
title: 'Metrics/PMC vertical: /api/metrics/pmc + MetricDaily in backend-v2'
status: in-progress
type: task
created_at: 2026-07-15T23:37:27Z
updated_at: 2026-07-15T23:37:27Z
parent: iron-trainer-eg0j
---

Port /api/metrics/pmc?days=N: MetricDaily entity (metrics_daily table), windowed read (default 180, 0=all, inclusive-of-today cutoff), {days, window_days, total_days} response. Parity vs FastAPI; reuses the existing test_pmc_windowing contract. Establishes MetricDaily for the readiness vertical next.
