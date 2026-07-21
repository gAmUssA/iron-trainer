---
# iron-trainer-k5d0
title: 'Decide §5.3 posture: decouple AI planner from Strava-derived data (N4)'
status: todo
type: task
priority: high
created_at: 2026-07-08T17:29:55Z
updated_at: 2026-07-21T23:54:45Z
parent: iron-trainer-cgoy
---

The last open item from the 2026-07-06 review (N4). Both plan generation and race-day nutrition (llm.py) send Strava-derived readiness/context to Claude — in tension with Strava API Agreement §5.3. Options analyzed in docs/research/strava-ingestion-and-ai.md:
- [x] Viktor decides → **(a) + Apple Health, keep Strava login** (2026-07-21)
- [ ] Implement the chosen option (includes generate_race_day_nutrition)

## New option discovered (2026-07-14): Apple Health as workout source

Health Auto Export's same REST payload carries a workouts array — and Garmin can feed workouts into Apple Health. That's a potential full Strava escape hatch: athlete-owned pipeline (Garmin → Apple Health → their phone POSTs to us), zero Strava API surface, no §5.3 exposure for AI features. Recovery ingestion (clye, in progress) builds the exact endpoint + parsing infrastructure this would need — workout ingestion would be an extension of the same pipeline. Evaluate after clye ships: fidelity of workout data (power/HR streams vs summaries) vs Strava's, and migration story.

## Decision (2026-07-21): (a) + Apple Health, phased; keep Strava login

**Posture:** decouple the LLM from Strava. §5.3 restricts *AI use* of Strava Data, NOT Strava API use generally — so Strava OAuth login + sync + display/metrics/dedup all STAY; only the LLM's inputs change. The clean, compliant story: the planner's Claude calls see only non-Strava-derived data (user-entered Fitness-Test thresholds + goals + Apple-Health-derived load/readiness). Native Apple Health ingestion is now live in prod, which is what makes this viable.

**Strava MCP connector (launched 2026-06-01)** — evaluated: it's the sanctioned §3.5 'bring your OWN AI to your OWN data' path (subscriber → their own AI client like Claude, read-only, OAuth, personal-use). It explicitly does NOT let a third-party app feed Strava data to its own LLM, so it is NOT a loophole for our server-side Claude calls — but it CONFIRMS the decouple direction. Follow-up idea (defer): point users at Strava's MCP connector for their own 'ask about my Strava' curiosity, while our planner stays Apple-Health-native.

**Not legal advice** — research (docs/research/strava-ingestion-and-ai.md) still flags counsel before any commercial launch. Residual gray area only on an aggressive reading of the undefined term 'AI Application'.

## Phase 1 — this bean (immediate §5.3 compliance)
- [ ] PlanAi.adjust / generateWeek: drop the Strava-derived context (fitness/zones/context = CTL/ATL/TSB, ACWR, planned-vs-actual compliance). Drive the LLM off user-entered thresholds (ftp/threshold_hr/threshold_pace_run/css_swim from Fitness Tests) + goals (weekly hours, race, phase).
- [ ] NutritionAi (race-day): drop the Strava-derived 'readiness' input (nutrition is body-weight/duration/intensity driven — low impact).
- [ ] Leave Strava login/sync/display/PMC charts fully intact.
- [ ] Trade-off: the LLM temporarily loses training-load context (deterministic template still handles periodization); Phase 2 restores it from Apple Health.

## Phase 2 — [[iron-trainer-yj6a]] (restore load signal, athlete-owned)
Apple Health workouts → Apple-Health-derived CTL/ATL/TSB → feed THAT to the LLM (not Strava Data). Sign-in-with-Apple ([[iron-trainer-3e6w--sign-in-with-apple-strava-free-auth-ios-backend]] (iron-trainer-3e6w)) gives a fully Strava-free account path for anyone who wants the airtight version.
