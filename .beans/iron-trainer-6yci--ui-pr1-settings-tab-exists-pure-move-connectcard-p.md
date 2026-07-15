---
# iron-trainer-6yci
title: 'UI PR1: Settings tab exists (pure move — ConnectCard + pairing + copy)'
status: done
type: task
created_at: 2026-07-15T22:48:10Z
updated_at: 2026-07-15T22:56:00Z
parent: iron-trainer-uywr
---

First of 3 IA-overhaul PRs (docs/research/ui-taxonomy.md). Pure move, no redesign: rename Thresholds tab → Settings; move ConnectCard + iOS pairing off Dashboard into Settings; add a thin sync-status line to Dashboard replacing the fused ConnectCard; fix ReadinessCard/Nutrition copy that points at 'Thresholds tab'; retarget tour anchors. Zero new components. Must build + screenshot clean.

## Checklist

- [x] Rename tab label "Thresholds" → "Settings" (kept `settings` id)
- [x] Move ConnectCard off Dashboard; render it at the TOP of the Settings tab
- [x] Add a thin sync-status line to Dashboard (no card) — `athlete.connected` + link to Settings, no new API call
- [x] Fix copy pointing at old tab: ReadinessCard ("Settings → Thresholds"), NutritionView ("Go to Settings")
- [x] Retarget tour: nav-step copy Trends/Settings; `#tour-setup` re-anchored to the Dashboard sync-line; ConnectCard id → `tour-connect` (no duplicate ids)
- [x] `npm run build` passes clean (tsc + vite)
- [x] Visual verification via seeded demo backend + Playwright (Dashboard + Settings screenshots)

## Summary of Changes

Pure move / rename — no new components, no restyle, no backend/API changes.

- **frontend/src/App.tsx** — TABS label Thresholds→Settings (id unchanged); removed ConnectCard + its `dash-top` grid wrapper from the Dashboard panel; added a thin `.sync-line` (status dot + "Strava connected · <name> — manage & sync in Settings →" / "Strava not connected — set up in Settings →", `setTab("settings")` link) using already-loaded `athlete` state; added ConnectCard at the top of the Settings panel.
- **frontend/src/components/Setup.tsx** — ConnectCard root id `tour-setup` → `tour-connect` (the tour's `#tour-setup` now anchors the Dashboard sync-line; avoids a duplicate id).
- **frontend/src/components/Dashboards.tsx** — ReadinessCard missing-data copy: "the Thresholds tab" → "Settings → Thresholds".
- **frontend/src/components/NutritionView.tsx** — button "Go to Thresholds" → "Go to Settings".
- **frontend/src/tour.ts** — nav step + comment reference Settings not Thresholds; "Connect & sync" step re-pointed at the Dashboard sync-line (`#tour-setup`), copy directs setup to the Settings tab, position right→bottom.
- **frontend/src/styles.css** — added `.sync-line` and `.linklike` rules. (`.dash-top` left in place but now unused on Dashboard — harmless; cleanup deferred.)

Verification: `npm run build` clean. Seeded a throwaway demo backend (scripts/seed_demo.py) on :8791 serving frontend/dist and drove it with Playwright — Dashboard shows the sync-line and no ConnectCard; Settings shows ConnectCard ("Setup") at top above Race / Thresholds / Zones / Health.

Deliberately left for later PRs: PR2 (Fitness consolidation — PMC/Readiness into Trends→Fitness, fold Tests in, extract chartTheme.ts, Dashboard→Today); PR3 (card grammar — StatTile/ChartCard/ActionCard/SetupRow, unify banners, remove inline styles). The informative "last sync Xh ago" variant of the sync-line was not added because jobsSummary is not fetched in App.tsx and adding it was out of scope (no new API calls).
