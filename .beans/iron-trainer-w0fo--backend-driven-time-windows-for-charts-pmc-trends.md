---
# iron-trainer-w0fo
title: Backend-driven time windows for charts (PMC, trends)
status: completed
type: feature
priority: normal
created_at: 2026-07-14T22:25:37Z
updated_at: 2026-07-15T00:29:14Z
---

Fitness & Form (PMC) and trend graphs currently fetch and render the athlete's entire history — after a bulk archive import that's years of daily rows shipped on every dashboard load. Add backend-driven windowing: range params with sane defaults on /api/metrics/pmc and /api/metrics/trends, and a range picker (3m/6m/1y/all) in the UI. Insights/verdicts keep using full history server-side; only chart point payloads are windowed.

## Summary of Changes

Server-side chart windows: /api/metrics/pmc and /api/metrics/trends take days (default 180, 0 = all, capped 3660). PMC returns window_days + total_days; trends windows only chart points while insights/verdicts/PRs always compute from full history. Web: RangePicker (3m/6m/1y/All) on Fitness & Form and Trends with independent state and targeted refetch. 4 new tests (181 total). Verified live: 30-day window truncates PMC rows and Run points while insight freshness still sees the full record; pickers exercised via Playwright. ADR 0017.
