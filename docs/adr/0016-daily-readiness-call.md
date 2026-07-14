# 0016 — Daily readiness call: ACWR-based go hard / go easy / rest

Date: 2026-07-14
Status: Accepted
Bean: iron-trainer-vhef

## Context

The app plans training weeks but gave no answer to the everyday question "should
I push or back off *today*?" TSB (form) existed in the PMC series, but it was a
chart value, not a decision. Community practice (the Athlete AI "recovery check"
pattern) and the sports-science literature converge on the acute:chronic
workload ratio (ACWR, Gabbett 2016) as the primary load-based readiness signal:
compare the last 7 days of load to the athlete's own ~4-week norm; risk climbs
past ~1.3 and sharply past ~1.5.

All inputs already existed: `MetricDaily` stores per-day TSS/CTL/ATL/TSB and is
rebuilt on every sync. No new data source, no new Strava traffic, no new tables.

## Decision

1. **Pure module `backend/app/readiness.py`.** `compute(metrics_rows, today)`
   derives, from the athlete's own history only:
   - ACWR = sum(TSS, last 7 days) / (sum(TSS, last 28 days) / 4), windows ending
     *yesterday* — this morning's logged session must not flip today's call.
   - A trailing hard-day streak (day ≥ max(50 TSS, 1.5× chronic daily avg)).
   - The call: `>1.5 → rest (red)`, `>1.3 → easy (amber)`, else TSB < −25 →
     easy (amber), else ≥2 consecutive hard days → easy (amber), else
     `hard (green)` (with a "you're fresh — key session" variant when ACWR < 0.8
     and TSB > +10). TSB thresholds mirror `planning.service._form_flag`.
   - **Staleness guard:** TSB can only modify the call when the metrics series
     is ≤3 days old. A lapsed sync leaves TSB frozen at its last value; a
     19-day-old −28 must not fabricate a "deep fatigue" amber (found live
     against the demo DB).
   - **Insufficient data honesty:** <14 days of history or chronic <30 TSS/wk →
     `insufficient_data`, no call, instead of a fake-precise number.
2. **Endpoint** `GET /api/metrics/readiness/today` (the pre-existing
   `/api/metrics/readiness` remains *race* readiness — projected splits).
3. **Weekly check-in** narrates the call ("Today's call: GO EASY — …") via
   `readiness.story_line()` and exposes the structured payload as
   `out["readiness"]` for clients.
4. **Planner integration:** `_fitness_summary()` now carries
   `readiness_today {call, acwr, reason}` into both the season-adaptation and
   week-generation prompts; the week-generation system prompt instructs the
   model to never schedule a key session against a rest/easy call and to keep
   ramps near +10%.
5. **Surfaces, signal-not-noise:** web dashboard banner (`TodayCall`) — green
   renders as one quiet line, amber/red get a tinted border; iOS Today view
   `ReadinessBanner` between the race countdown and today's session, fetched
   best-effort (no banner on failure). Both show the pill (GO HARD / GO EASY /
   REST) plus the one-line reason with the actual numbers.

## Alternatives considered

- **EWMA-based ACWR (ATL/CTL).** Our CTL constant is 42 days, not the 28 the
  ACWR literature calibrates its bands against; mixing them would make the
  1.3/1.5 thresholds unfounded. Rolling sums are the classic, defensible form.
- **Persisting daily calls.** Rejected for now — the call is a pure function of
  stored metrics; recomputing is cheap and can't go stale. History/flags can
  come later with the recovery-inputs work (iron-trainer-clye).
- **Sleep/HRV/RHR inputs.** Deliberately out of scope: load-only readiness ships
  value today; richer recovery signals arrive via Health Auto Export /
  HealthKit ingestion (bean iron-trainer-clye) and will slot in as additional
  modifiers.

## Consequences

- The readiness vocabulary (call/level/reasons) is now shared backend → web →
  iOS → LLM prompts; future recovery inputs only need to extend `compute()`.
- Widget snapshot integration deferred (bean todo notes it) — the banner ships
  in-app first.
- Verified end-to-end: 13 unit/integration tests; live demo backend (endpoint +
  web banner via Playwright, iOS banner in the simulator via deep-link pairing).
