---
# iron-trainer-uywr
title: 'UI overhaul: information architecture & data taxonomy rethink'
status: todo
type: feature
priority: normal
created_at: 2026-07-15T03:53:12Z
updated_at: 2026-07-15T23:10:07Z
---

The dashboard is getting busy — cards accrete per feature (setup, race readiness, today's call, check-in, recovery, PMC…) without an information hierarchy. Step back and design the taxonomy: what data families exist (plan / execution / body / race), what belongs on which surface (dashboard vs trends vs settings), what's glanceable vs drill-down, and a consistent card grammar (stat tile vs chart vs action card). Deliverable: IA proposal + restructured navigation before adding more cards. Cross-cutting — spans web and iOS.

## Research complete (2026-07-15) → docs/research/ui-taxonomy.md

5-tab IA (Today/Plan/Fitness/Fuel/Settings, was 6): tabs = questions not features. Dashboard mixes 3 cadences; 'Thresholds' tab is really Settings. 4 card types max (StatTile/ChartCard/ActionCard/SetupRow). 3 shippable PRs ~6d: (1) Settings exists — pure move ~1d; (2) Fitness consolidation ~1.5d; (3) card grammar ~2d. Ready to start PR1 anytime.

## PR1 shipped (2026-07-15)

PR #40 merged: Settings tab exists — ConnectCard + iOS pairing moved off Dashboard into Settings, Thresholds→Settings rename, thin sync-line on Dashboard, copy fixes, tour retargeted (bean 6yci). PR2 (Fitness consolidation) + PR3 (card grammar) remain.
