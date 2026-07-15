# Web UI information-architecture overhaul (bean iron-trainer-uywr, 2026-07-15)

Researched with the ui-ux-pro-max skill for dashboard/IA patterns + full
inventory of current surfaces. Verdict: tabs should map to QUESTIONS not
features; the Dashboard currently interleaves three cadences (daily glance /
weekly ritual / one-time setup) and there is no predictable Settings home.

## Core diagnosis

- Dashboard mixes daily-glance (TodayCall, Recovery), weekly ritual (Check-in,
  PMC), and one-time setup (ConnectCard = 3 fused cards: checklist + sync ops +
  iOS pairing).
- Tab labeled "Thresholds" has id `settings` in code and holds race choice,
  nutrition inputs, ingest tokens — none are thresholds. ReadinessCard even
  tells users to visit "the Thresholds tab" for missing data. No findable
  Settings destination.

## Taxonomy: data family → canonical question

- **Today** — "What do I do today, am I recovered?" (TodayCall + today's
  session + Recovery + Check-in-when-due)
- **Plan** — "What's the week/block, did I execute it?" (PlanView, compliance)
- **Fitness** — "Am I getting fitter, will I be ready?" (PMC, sport trends,
  CTL trajectory, intensity, PRs, Tests, race Readiness projection)
- **Fuel** — "What/how much to eat & drink?" (Nutrition)
- **Settings** — "Is the pipeline healthy, are my inputs right?" (Strava, iOS
  pairing, health ingest, race, thresholds, zones, units)

## Navigation: 5 tabs (was 6)

`Today · Plan · Fitness · Fuel · Settings`. Dashboard→Today (its only surviving
job); Trends+Tests+PMC+Readiness collapse into Fitness (one question, one
cadence); Thresholds→Settings (an honest destination). Mirrors what the iOS
Today view already does well: verdict first, one action, no admin.

## Card grammar: exactly 4 types

1. **Stat tile** — label / big value+unit / delta / optional sparkline
   (RecoveryCard tiles + PrCards → one component).
2. **Chart card** — title / sub / kpi-legend + RangePicker / chart
   (PMC, sport trends, volume, intensity already converge — codify).
3. **Action card** — title / one-line promise / single primary button /
   result narration (CheckinCard is the template; Plan+Nutrition adopt).
4. **Setup row** — status dot / label / action / hint, stacked in Settings.

Inconsistencies to kill: card-label vs card-title vs rd-label; inline styles
in Setup.tsx; useChart/CHART_THEME/COLORS duplicated across Dashboards.tsx +
TrendsView.tsx → extract chartTheme.ts; 3 ad-hoc banner variants.

## Migration: 3 shippable PRs (~6 days), no big-bang

- **PR 1 — "Settings exists"** (~1d, pure move): rename Thresholds→Settings;
  move ConnectCard + iOS pairing off Dashboard into Settings; add a thin
  sync-status line to Dashboard; fix ReadinessCard/Nutrition copy; retarget
  tour anchors. Zero new components.
- **PR 2 — "Fitness consolidation"** (~1.5d): move PMC + Readiness to the
  Trends tab, rename it Fitness, fold Tests in as a section; extract
  chartTheme.ts; Dashboard becomes Today (+ today's-session card derived from
  already-fetched plan, ~1d).
- **PR 3 — "Card grammar"** (~2d): introduce StatTile/ChartCard/ActionCard/
  SetupRow; migrate cards; unify banners; remove inline styles.

Follow-up bean: check-in "due" logic needs the last-checkin timestamp
(available via jobsSummary().latest["checkin"]) — 0.5d.
