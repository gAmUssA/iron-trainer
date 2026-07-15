---
# iron-trainer-rero
title: 'UI PR2: Fitness consolidation (Trends→Fitness, fold in PMC+Readiness+Tests, extract chartTheme)'
status: completed
type: task
priority: normal
created_at: 2026-07-15T23:10:08Z
updated_at: 2026-07-15T23:18:09Z
---

Second of 3 IA-overhaul PRs (docs/research/ui-taxonomy.md). Consolidate everything that answers 'am I getting fitter / will I be ready?' onto one tab.

## Scope
- [x] Rename the Trends tab -> Fitness (keep the 'trends' id)
- [x] Move PmcChart (PMC) into the Fitness tab
- [x] Move ReadinessCard (race readiness projection) into the Fitness tab
- [x] Fold the Tests tab in as a section of Fitness; drop the standalone Tests tab
- [x] Extract duplicated chart theming (useChart/CHART_THEME/COLORS in Dashboards.tsx + TrendsView.tsx) into a shared chartTheme.ts
- [x] Dashboard keeps only the daily glance: TodayCall, sync-line, Recovery, Check-in (PMC + Readiness leave)
- [x] Retarget the tour (nav copy + #tour-pmc / #tour-readiness anchors now live under Fitness)
- [x] npm run build clean; visual verification via demo backend + Playwright
- [x] Write an ADR of decisions + implementation

No card-grammar component work (that is PR3). No backend/API changes.

## Summary of Changes

- **frontend/src/chartTheme.ts** (new) — shared `COLORS` (union of swim/bike/run
  + PMC ctl/atl/tsb + projection accent) and the `useChart()` hook + light/dark
  `CHART_THEME`, previously duplicated verbatim in Dashboards.tsx and
  TrendsView.tsx (whose two `COLORS` objects had already drifted).
- **frontend/src/components/Dashboards.tsx** — dropped the local
  COLORS/CHART_THEME/useChart + now-unused `useTheme` import; import from
  `../chartTheme`.
- **frontend/src/components/TrendsView.tsx** — same extraction; kept `IF_COLORS`
  (intensity-mix only) local.
- **frontend/src/App.tsx** — `Tab` type and `TABS` drop `tests`; `trends` label
  Trends → Fitness (id kept). Fitness panel renders ReadinessCard → PMC (or the
  connect-Strava placeholder) → TrendsView → TestsView. Dashboard panel loses
  ReadinessCard + PmcChart (now a lean daily glance: TodayCall, sync-line,
  Recovery, Check-in). Standalone Tests panel removed.
- **frontend/src/tour.ts** — nav-step copy lists the five tabs and folds in the
  readiness/PMC descriptions; removed the two now-unreachable steps
  (`#tour-readiness`, `#tour-pmc`) since the tour only runs on the Dashboard.
- **docs/adr/0021-web-ui-information-architecture.md** (new) + README index row.

No backend/API changes; no card-grammar components (PR3).

Verification: `npm run build` clean (tsc + vite). Driven in a real browser
(Playwright, seeded demo backend): tab bar is the five expected entries;
Dashboard shows only the daily glance; Fitness renders Race Readiness, PMC,
freshness banner, sport verdicts + range, the three sport-trend charts +
Fitness Trajectory, Weekly Volume + Intensity Mix, Personal Records, and the
folded-in Fitness tests protocols — all charts drawing.

## Deferred / follow-ups
- Dashboard → Today rename + a today's-session card derived from the fetched
  plan (research doc scopes this as a separable ~1d piece). Follow-up bean:
  iron-trainer-6xpm.
- Merging the moved PMC RangePicker with the Trends RangePicker — belongs with
  PR3 (card grammar).
