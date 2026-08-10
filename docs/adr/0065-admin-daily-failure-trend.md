# 0065 — Admin: per-day failure trend sparkline (2026-08-09)

- **Status:** Accepted
- **Bean:** 8vdj (admin epic 18n4; last of the sync-health follow-ups from ADR 0058)

## Context

The Health tab shows per-kind failure rates for the window and p50/p95 durations
(ADR 0064), but not whether failures are trending up or down over the window. The
1/7/30d window switch was the stand-in.

## Decision

- **`GET /api/admin/health/jobs`** now returns a `daily` array — per calendar day in
  the window: `total`, `failed`, `failure_rate` — oldest→newest. Built from one
  `group by substring(created_at,1,10), status` query (created_at is ISO, so its
  first 10 chars are the UTC date), folded to per-day totals in Java. **Sparse**:
  days with no jobs are omitted.
- **Frontend:** a "Daily failure trend" `MiniSpark` (reused from the recovery views)
  on the Health tab — failure-rate % per day. Shown only when there's data.

## Alternatives considered

- **Per-kind daily trend** — heavier (a series per kind); the overall daily failure
  rate answers "is it getting worse" at a glance. Per-kind can follow if needed.
- **Fill missing days server-side** for a continuous axis — skipped; the sparse
  series with date-labeled points is enough for a trend indicator (`ponytail:` fill
  the gaps if the sparkline ever needs an even time axis).

## Consequences

- The admin sync-health view now shows failure *direction*, not just the current
  window snapshot. Completes the sync-health follow-ups; the admin epic (18n4) has no
  open children left.

## Verification

`AdminHealthResourceTest.aggregatesWindowAndRecentFailures` extended: today's bucket
reflects the seeded runs (total ≥ 5, failed ≥ 2) and the out-of-window ancient day is
absent from `daily`. Frontend builds. Local review + Copilot before merge.
