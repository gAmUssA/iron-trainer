# 0017 — Backend-driven time windows for charts

Date: 2026-07-14
Status: Accepted
Bean: iron-trainer-w0fo

## Context

`GET /api/metrics/pmc` returned every `MetricDaily` row and `GET
/api/metrics/trends` charted every activity — the athlete's entire history on
every dashboard load. After a bulk Strava archive import that's years of daily
rows and thousands of chart points shipped and rendered each time.

## Decision

Time-series charts get a **server-side window**, not client-side pagination —
"page 3 of your fitness curve" is meaningless; a time range is the natural
unit.

1. Both endpoints accept `days` (`Query(ge=0, le=3660)`), default
   `DEFAULT_WINDOW_DAYS = 180`; `days=0` means full history.
2. `/metrics/pmc` returns `{days, window_days, total_days}` — `total_days`
   lets the UI distinguish "no data at all" from "no data in this window".
3. `/metrics/trends` windows **only the chart points**. Insights, verdicts,
   PRs, freshness, and the CTL trajectory are always computed from the full
   record first — a narrow window must not flip a verdict or lose a PR.
4. Frontend: shared `RangePicker` (3m / 6m / 1y / All) on the Fitness & Form
   card and the Trends page, with independent range state; a range change
   refetches only the affected series, not the whole dashboard.

## Alternatives considered

- **Offset/limit pagination.** Wrong shape for time series; nobody browses
  CTL by page.
- **Client-side windowing (fetch all, slice in JS).** Fixes rendering cost but
  not payload size, which is the actual problem after an archive import.
- **Downsampling (e.g. LTTB) instead of windowing.** Complementary, not
  competing; can be added later for the `All` view if multi-year histories
  make it sluggish.

## Consequences

- Default dashboard payloads are bounded (~180 PMC rows) regardless of history
  size.
- API consumers that relied on the implicit full history must pass `days=0`
  (checked: web app was the only consumer; iOS uses the `.itw` export path).
- `weekly` already had a `weeks` param; unchanged.
