---
# iron-trainer-4j7p
title: Generalize races beyond triathlon (run / ride / swim / multisport)
status: todo
type: epic
priority: normal
created_at: 2026-07-22T21:48:30Z
updated_at: 2026-07-22T21:48:30Z
---

Support single-sport events (marathon/half, century/gran fondo, open-water swim) + custom distances + (P3) multisport & multi-race seasons, chosen during race setup. Research: docs/research/custom-races.md (bean wb53) — VERIFIED 2026-07-22 to still map to backend-v2 (the '6 tri-constants' survived the Python→Java port).

## Verified tri-hardcoding (backend-v2)
1. PlanTemplate SWIM/BIKE/RUN_SPLIT (20/50/30) + fixed 6-slot swim+bike+run week.
2. Races.cutoffsFor — keyed 70.3/140.6, falls back to tri cutoffs for any string.
3. PlanValidator — taper weeks / session caps / max week hours (century ride clamps).
4. PlanAi/NutritionAi personas ('IRONMAN 70.3 coach'), closed sport enum, 7 tri nutrition phases.
5. RaceReadiness — 3-leg projection + fixed T1/T2 transitions.
6. Nutrition swim→T1→bike→T2→run timeline; RaceCard.tsx limited to 70.3/140.6. (iOS already race-agnostic.)

## Design (additive)
- Race model: add event_type (tri|run|ride|swim|multisport) + legs_json [{sport, distance_m}]; cutoffs optional (auto for tri). Backfill event_type=tri (41 seeded races unchanged). Athlete gains goal(finish|time)+goal_time_s; A/B/C priority in P3. NOTE prod gotcha: Flyway migrate-at-start OFF in prod → manual Supabase ALTER before deploy ([[backend-v2-railway-deploy]]).
- ONE EVENT_PROFILES config: per event_type → sport_weights, taper_weeks, brick flag, session_caps, max_week_hours, week_pattern, quality_count, persona. PlanTemplate + PlanValidator + LLM prompts consume it; current validator constants become tri's defaults; new rule: no Brick unless profile.brick.
- RaceReadiness iterates legs (per-sport projectors already exist); transitions only when legs>1; single finish cutoff for single-sport. Nutrition: single-sport = pre→race→post.

## Setup/UI
- Race setup asks race TYPE first → type-appropriate distance picker → drives plan/readiness/nutrition/UI.
- UI is ADAPTIVE (recommended), not fully separate: one readiness/plan/nutrition UI that iterates legs + hides transitions/other sports for single-sport. Tailored per-sport views optional in P3.

## Phasing
- [ ] P1: run races (marathon/half/custom-km) end-to-end (~2-3d)
- [ ] P2: ride (century/gran fondo) + swim (OWS) (~1d, reuses P1)
- [ ] P3: custom multisport + multi-race season (athlete_race join, A/B/C) (~3-5d — only phase changing planner SHAPE)
