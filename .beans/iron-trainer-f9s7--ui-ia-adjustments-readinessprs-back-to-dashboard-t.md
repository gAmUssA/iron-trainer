---
# iron-trainer-f9s7
title: 'UI IA adjustments: readiness+PRs back to Dashboard, Tests own tab, Fitness after Plan, 2-col Settings, fix Strava button clip'
status: completed
type: task
priority: normal
created_at: 2026-07-15T23:50:16Z
updated_at: 2026-07-15T23:50:56Z
---

Post-PR2 product-owner UI tweaks (follows PR #40/#42).

- [x] Bring Race Readiness back to the Dashboard (first tab)
- [x] Put Personal Records on the Dashboard after Readiness (exported PrCards from TrendsView; reuses trends.insights.prs already in hand)
- [x] Recovery stays on the Dashboard (order: Readiness -> Personal Records -> Recovery -> Check-in)
- [x] Keep Tests as its own tab (un-fold from Fitness)
- [x] Reorder tabs so Fitness comes right after Training Plan (Dashboard, Training Plan, Fitness, Nutrition, Tests, Settings)
- [x] Settings: Thresholds and HR Zones as two columns in one row (grid-2)
- [x] Fix the 'Connect with Strava' button clipping (SVG text overflowed the 200-wide viewBox; widened to 224)
- [x] Retarget tour (readiness step back on Dashboard; nav copy)
- [x] build + Playwright visual verification

## Summary of Changes

- **frontend/src/App.tsx** — `Tab`/`TABS` add `tests` back and reorder to
  Dashboard · Training Plan · Fitness · Nutrition · Tests · Settings. Dashboard
  panel renders TodayCall → sync-line → ReadinessCard → PrCards → Recovery →
  Check-in. Fitness panel is now PMC + TrendsView only (no readiness/PRs/tests).
  Tests panel restored. Settings wraps ProfileEditor + ZonesCard in `grid-2`.
  Imports `PrCards` from TrendsView.
- **frontend/src/components/TrendsView.tsx** — `PrCards` exported; removed its
  render from the Fitness view (moved to Dashboard).
- **frontend/src/tour.ts** — restored the `#tour-readiness` step (readiness back
  on the Dashboard); nav copy updated for the six tabs / new order.
- **frontend/public/strava/btn_strava_connect.svg** — widened viewBox/rect
  200→224 so the "Connect with Strava" label is no longer clipped (used by the
  Settings ConnectCard and LoginScreen).
- **docs/adr/0021-web-ui-information-architecture.md** — appended a dated
  "post-merge IA refinement" section documenting these placement changes.

No backend/API changes.

Verification: `npm run build` clean (tsc + vite). Driven in a real browser
(Playwright, seeded demo backend): tab order correct; Dashboard shows Race
Readiness then Personal Records; Fitness has only PMC + trends; Tests is its own
tab; Settings shows Thresholds and HR Zones side by side; the Connect-with-Strava
button renders in full.
