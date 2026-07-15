# 0021 — Web UI information architecture: tabs map to questions

Date: 2026-07-15
Status: Accepted
Beans: iron-trainer-6yci (PR1), iron-trainer-rero (PR2)
Research: docs/research/ui-taxonomy.md

## Context

The web dashboard accreted one card per feature. Three cadences were
interleaved on a single Dashboard tab — daily glance (TodayCall, Recovery),
weekly ritual (Check-in, PMC), and one-time setup (the fused ConnectCard:
setup checklist + Strava sync/import/dedup + iOS pairing). The tab bar carried
six entries — `Dashboard · Training Plan · Nutrition · Trends · Tests ·
Thresholds` — and the "Thresholds" tab (id `settings` in code) actually held
race choice, nutrition inputs and ingest tokens, none of which are thresholds.
ReadinessCard even told users to visit "the Thresholds tab" for missing data.
There was no findable Settings home, and no predictable place for "am I getting
fitter?".

## Decision

Tabs map to the **question a user is asking**, not to features. Target
taxonomy (from the research doc):

- **Today** — "What do I do today, am I recovered?"
- **Plan** — "What's the week/block, did I execute it?"
- **Fitness** — "Am I getting fitter, will I be ready?"
- **Fuel** — "What/how much to eat & drink?"
- **Settings** — "Is the pipeline healthy, are my inputs right?"

Migrate in **three shippable PRs**, never a big-bang rewrite:

- **PR1 — "Settings exists"** (pure move, zero new components): rename the
  Thresholds tab → Settings (keep id `settings`); move ConnectCard + iOS
  pairing off the Dashboard to the top of Settings; replace it on the Dashboard
  with a thin sync-status line; fix copy pointing at "the Thresholds tab";
  retarget the tour. Shipped (#40).
- **PR2 — "Fitness consolidation"** (this branch): rename Trends → Fitness
  (keep id `trends`); move the race-readiness projection (ReadinessCard) and the
  PMC fitness/form chart onto it; fold the standalone Tests tab in as a section;
  extract the duplicated chart theming into a shared `chartTheme.ts`. The
  Dashboard is left holding only the daily glance (TodayCall, sync-line,
  Recovery, Check-in). Tab bar drops from six to five.
- **PR3 — "Card grammar"** (planned): introduce the four card types
  (StatTile / ChartCard / ActionCard / SetupRow), migrate cards, unify the
  three ad-hoc banner variants, remove inline styles.

## Alternatives considered

- **One big redesign PR.** Rejected: high blast radius, hard to review, hard to
  revert; the taxonomy can be reached incrementally with each step shippable.
- **Keep Tests as its own tab.** Rejected: testing thresholds is part of "am I
  getting fitter / are my inputs right?" and did not warrant a top-level tab;
  folding it into Fitness removes a tab and colocates it with the trends it
  informs.
- **Rename Dashboard → Today and add a derived today's-session card in PR2.**
  Deferred: the research doc itself scopes that as a separable ~1d piece. Doing
  it here would widen PR2's blast radius; tracked as follow-up so PR2 stays
  focused on the Fitness merge.
- **Merge PMC's range picker with the Trends range picker in PR2.** Deferred to
  PR3 (card grammar) — for now the moved PMC keeps its own `RangePicker`
  alongside the Trends one. Two pickers on one tab is acceptable interim state.

## Consequences

- The Dashboard is now a genuine daily glance; Fitness answers one question at
  one cadence; Settings is an honest, findable destination.
- Tab count 6 → 5. Tab **ids are preserved** (`settings`, `trends`) so state,
  deep-links and the tour keep working across the rename.
- Chart theming lives in one module; the dashboard and trends charts can no
  longer drift apart (they had already diverged: the two `COLORS` objects were
  not identical).
- The product tour runs only on the Dashboard, so cards that moved off it
  (ConnectCard in PR1; ReadinessCard + PMC in PR2) can no longer be anchored
  there. Their walkthrough content was folded into the nav step rather than
  left to silently drop.
- Interim rough edges (two range pickers on Fitness; ad-hoc banners; inline
  styles) are accepted and explicitly owned by PR3.

## Implementation notes (PR2)

- `frontend/src/chartTheme.ts` (new): exports `COLORS` (union of swim/bike/run
  + PMC ctl/atl/tsb + projection accent) and the `useChart()` hook + light/dark
  `CHART_THEME`. `Dashboards.tsx` and `TrendsView.tsx` had verbatim copies of
  the hook and theme and near-duplicate `COLORS`; both now import from here.
  `IF_COLORS` (intensity-mix only) stays local to `TrendsView.tsx`.
- `App.tsx`: `Tab` type and `TABS` drop `tests`; `trends` label → "Fitness".
  The Fitness panel renders ReadinessCard → PMC (or a connect-Strava
  placeholder) → TrendsView → TestsView. The Dashboard panel loses ReadinessCard
  and PmcChart. The standalone `tests` panel is removed.
- `tour.ts`: nav-step copy now lists the five tabs and folds in the
  readiness/PMC descriptions; the two now-unreachable steps (`#tour-readiness`,
  `#tour-pmc`) were removed.
- No backend/API changes. No card-grammar component work (that is PR3).

## Verification

- `npm run build` clean (tsc + vite) on PR1 and PR2 branches.
- PR2 driven in a real browser against a seeded demo backend (Playwright):
  Dashboard renders only the daily glance (verdict + sync-line); the Fitness
  tab renders, top to bottom, Race Readiness, the PMC Fitness & Form chart, the
  freshness banner, sport verdicts + range picker, the Bike/Run/Swim trend
  charts + Fitness Trajectory, Weekly Volume + Intensity Mix, Personal Records,
  and the folded-in Fitness tests protocols. All charts draw. Tab bar shows the
  five expected entries with no Tests/Trends leftovers.
