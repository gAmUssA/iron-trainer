# ADR 0011 — Trends page: insights instead of sparklines

**Status:** Accepted · 2026-07-06

## Context

Two complaints about the Trends tab: (1) it "wasn't updating" — in reality the
data ends at the last manual sync (there is deliberately no background sync; see
the Strava rate-limit posture in ADR 0008), but nothing on the page said so, so
stale data read as a broken page; (2) the charts were three 88-px sparklines of
raw per-activity points — no aggregation, no direction, no context — and the
answer to "am I getting fitter?" wasn't on the page that exists to answer it.

Constraint: we store per-activity **summaries** only (no streams), so every
insight must be derivable from avg power/HR/speed, distance, moving time, the
stored per-session intensity factor, and the daily CTL/ATL table.

## Decision

New pure-function module `app/insights.py` (same testable-math design as
`dashboards.py` / `nutrition.py`), served as an `insights` object added to the
existing `GET /api/metrics/trends` response (raw point keys unchanged — no
breaking change). Frontend: new `TrendsView` component replaces the sparkline
stack.

1. **Freshness, not silence** — `freshness` reports the last activity date and
   days stale; the page shows a banner ("Data ends Jun 29 — 7 days ago") with a
   one-click **Sync now** when ≥3 days stale and Strava is connected.
   *Alternative considered:* background auto-sync — rejected for now (token
   refresh on a timer, rate-limit budget, and surprise API traffic; the banner
   makes staleness a one-click problem instead).
2. **Verdicts, not vibes** — per sport, a least-squares regression over the last
   12 weeks yields "improving / declining / steady (±1.5%)". Bike and run judge
   **efficiency factor** (output per heartbeat) when HR data exists — a fitness
   signal independent of how hard the athlete happened to ride — falling back to
   raw power/pace; swim judges pace. Direction is orientation-aware (falling
   pace = improving).
3. **Trendline over noise** — each sport chart shows sessions as faint dots with
   a 28-day trailing-mean line on top, real axes, and unit-aware formatting.
4. **Intensity mix** — weekly hours bucketed by stored session IF (<0.70 easy,
   0.70–0.85 endurance, 0.85–0.95 tempo, ≥0.95 hard): shows polarization vs
   grey-zone mush at a glance.
5. **CTL trajectory** — current CTL, 4-week ramp rate, and a straight-line
   projection to race day (dashed) on top of the CTL history, labeled as a
   projection with the taper caveat.
6. **Personal records** — bests with floors so sprints/GPS blips can't claim
   them: bike power ≥40 min, run pace ≥5 km, swim pace ≥1 km, longest ride/run.

## Consequences

- `/api/metrics/trends` payload grows (~kB); still one query pass over
  activities + metrics, all O(n·window) math, no schema change.
- The regression verdict needs ≥4 points in 12 weeks per sport, else
  "insufficient data" — honest on thin history.
- The projection is linear by design; it will overshoot through a taper. The
  caption says so rather than pretending to model periodization.

## Verification

- `tests/test_insights.py` — 11 tests: rolling-window mean, slope sign/magnitude
  and <4-point refusal, pace-vs-power verdict orientation, EF-preference for
  bike, IF bucketing, PR floors (30-min ride can't take the 40-min power PR),
  CTL ramp + projection closed-form, freshness.
- Full suite + ruff green; `npm run build` green.
- Rendered end-to-end against a seeded 16-week demo DB and visually inspected
  via Playwright (light + dark): verdict badges, trendlines, trajectory with
  RACE marker, PR cards all correct — including Swim showing change −9.3% with
  verdict "improving" (orientation logic live).
