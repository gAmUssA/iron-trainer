---
# iron-trainer-6xpm
title: 'UI PR2b: Dashboard -> Today (+ today''s-session card from plan)'
status: completed
type: task
priority: normal
created_at: 2026-07-15T23:17:57Z
updated_at: 2026-07-22T04:10:34Z
parent: iron-trainer-uywr
---

Follow-up split out of PR2 (iron-trainer-rero) per docs/research/ui-taxonomy.md. Rename the Dashboard tab -> Today and add a today's-session card derived from the already-fetched plan state (no new API calls). Scoped separately so the PR2 Fitness consolidation stayed focused. ~1d.

## Implemented (2026-07-21)
- Tab label Dashboard → Today (id kept 'dashboard' for state/deep-link stability, like PR1 kept 'settings').
- New TodaySessionCard (components/TodaySessionCard.tsx): today's planned session(s) derived from the already-fetched plan.workouts (localToday() vs UTC to avoid tz shift) — NO new API call. Rest-day aware ('Rest day — recover'); only rendered when a plan exists. Placed after TodayCall (verdict first, then today's action) with an 'Open in Training Plan →' link.
- Shared src/sport.ts (SPORT map + fmtDur) extracted from PlanView so the card reuses the exact badge styling instead of a 3rd copy (uywr flags the drift; full unify is PR3). PlanView imports from it.
- Copy: tour nav text + comments Dashboard→Today.
- Frontend build: tsc -b + vite clean. Live screenshot deferred (needs a data-populated authed session); eyeball on the front door after merge.

## Review fix (2026-07-21)
[CONFIRMED] the card reused .session-meta, which styles.css hides under 640px — so duration/intensity/TSS vanished on phones (the Today view's target device). Added a dedicated .today-session-meta (same look, always visible, wraps instead of nowrap) + switched the card to it. Rebuild clean.
[PLAUSIBLE, left as-is] fmtDur 0→'—': pre-existing PlanView behavior; duration_s is never 0 for a generated workout and '—' reads as 'no duration set'.
