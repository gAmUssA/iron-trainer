# 0047 — Recovery trends (web) + readiness glance widget (iOS) (2026-07-21)

Date: 2026-07-21
Beans: iron-trainer-rp3t (web recovery trends) · iron-trainer-<glance> (iOS widget)
Related: ADR 0045/0046 (HAE metrics — the data these visualize)

## Context

Two complementary "at a glance" surfaces, both **extending existing infrastructure**
(not greenfield):

- **Web** already has a *Fitness* tab (recharts) with PMC (CTL/ATL/TSB), per-sport
  trends, PRs, and a CTL-to-race projection. It answers *"am I getting fitter?"*
  It does NOT trend **recovery** — recovery only shows as a current-day snapshot
  card. With the expanded HAE metrics (ADR 0046: HRV, RHR, sleep stages, weight,
  VO₂max, SpO₂, HR-recovery, activity load) there's now a rich recovery series to
  visualize. This is the concrete `rp3t` scope (originally "replicate Grafana").
- **iOS** already ships a WidgetKit extension (`RaceCountdownWidget`,
  `TodayWorkoutWidget`) fed by a snapshot pipeline: the app writes a Codable
  `WidgetSnapshot` to the App Group `group.io.gamov.irontrainer`, the widget only
  reads it (no network in the extension — Apple's recommended pattern). The app
  already fetches `ReadinessToday`/`RecoveryDay` but they're not in the snapshot.

## Decision A — Web: a "Recovery" trends view (`rp3t`)

Add a **Recovery** tab beside *Fitness* (the two split cleanly: Fitness = load,
Recovery = readiness). Reuse the house chart system verbatim — `chartTheme.ts`
(`useChart()`, `COLORS`), `<div className="card">`, `ResponsiveContainer`,
`RangePicker` (3m/6m/1y/All), the seq-guard fetch pattern, and `api.recovery(days)`
+ `api.readinessToday()`. Panels (all recharts, all fed by `/api/health/recovery`
unless noted):

1. **HRV + RHR trend** — the two core recovery signals, dual-axis line, with a
   7-day rolling mean overlay (matches the sport-trend rolling style).
2. **Sleep stacked bar** — deep/rem/core(+awake) hours per night (the Grafana
   "Sleep Analysis" panel), colored by stage.
3. **Body weight trend** — line, light rolling mean.
4. **Activity load** — steps + active energy + Apple-exercise minutes (the new
   ADR-0046 metrics), combined bar/line.
5. **VO₂max / SpO₂ / respiratory** — secondary vitals lines (compact row).
6. **Readiness strip** (optional v2) — the daily go-hard/easy/rest `call` colored
   by `level` across the window, from a small readiness history.

Gaps acknowledged: no **workout route map** panel — the `Activity` entity has no
GPS/route columns (only `raw_json`); deferred. No new backend endpoints needed for
v1 (recovery + readiness already exist); a `/api/metrics/recovery-trends` rollup is
a *possible* later optimization, not required.

## Decision B — iOS: a readiness/recovery glance widget

Extend the snapshot pipeline (no new Keychain access group — the app fetches, the
widget reads the App Group file, exactly as today):

1. **`WidgetSnapshot`** (`ios/Shared/WidgetSnapshot.swift`) — add a `readiness`
   struct: `call` (hard/easy/rest), `level` (green/amber/red), `hrvMs`, `rhrBpm`,
   `ctl`, `atl`, `tsb`, `reason` (reasons[0]), plus `generatedAt`. Today's workout
   is already present.
2. **`ImportModel`** — on the existing plan refresh, also fetch
   `/api/metrics/readiness/today` (+ latest `/api/metrics/pmc` row for CTL/ATL/TSB,
   `/api/health/recovery?days=1` for HRV/RHR — the app already has these types),
   populate `snapshot.readiness`, `SharedStore.write`, `WidgetCenter.reloadAllTimelines()`.
3. **New `ReadinessWidget`** in the bundle, families:
   - `.systemSmall` — level-colored, big `call` word + HRV/RHR + today's sport dot.
   - `.accessoryCircular` (Lock Screen / StandBy) — a level-colored gauge ring with
     the call glyph.
   - `.accessoryRectangular` (Lock Screen) — `call` + "HRV 55 · RHR 48" + `reason`.
   - `.accessoryInline` — "Rest · HRV 55".
   - `.containerBackground(for: .widget)` on every view (required iOS 17+ for
     StandBy / Lock Screen placement).
4. **Timeline** — reuse `SnapshotProvider` (one entry per upcoming midnight so the
   day rolls over without reloads; readiness refreshes when the app writes). Well
   within the ~40–70 reloads/day budget.

Deliberately deferred: **Control Center control** (a "Sync Strava" / "Check-in"
control) — needs an App Intent doing network, a bigger, interactive slice; note as
follow-up. Any iOS 26-only widget niceties are `@available`-gated additions, not
required for the iOS 18 target.

## Consequences

- Recovery data (finally rich after ADR 0046) becomes visible as trends — closes
  the loop on the whole HAE thread.
- The glance widget reuses the proven snapshot pattern; zero network/Keychain
  changes in the extension, low regression risk. Xcode build/test is Viktor-driven
  (device required for real widget testing per WidgetKit guidance).
- Both are additive; no backend or schema changes for v1.
