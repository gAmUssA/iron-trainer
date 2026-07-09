# ADR 0014 — Weekly Check-in: one-tap adaptive loop

**Status:** Accepted · 2026-07-09

## Context

Every piece of the adaptive-coaching loop existed — Strava sync, activity↔plan
matching, compliance, TSB form flags, week replanning, fitness-test recency —
but exercising it took three manual actions on different screens, and the
results (why did next week change?) were never narrated. The loop that makes
the app a coach instead of a toolbox was technically present and practically
unused.

## Decision

`POST /api/plan/checkin` composes existing machinery — deliberately adding no
new state:

1. **Sync, best-effort**: `run_sync(full=False)`; not-connected or a Strava
   error degrades to a story line ("using local data"), never an abort.
2. **Reconcile + replan**: the existing `reconcile(weeks_ahead=1)` (match
   actuals → compliance → replan next week with form/compliance context; the
   in-progress week stays untouched, as before).
3. **Hours delta**: next week's planned hours are snapshotted before/after the
   replan so the story can say "eased from 8.1h to 7.3h" — computed from
   workout durations, not week targets, so LLM adjustments are captured.
4. **Test-due nudges**: any protocol whose sport hasn't been tested within
   `RETEST_DAYS` (35) is surfaced — the thresholds drive everything else.
5. **Key sessions**: top-2 upcoming workouts by planned TSS.

The response carries structured fields plus a `story: [str]` — the narrative is
assembled server-side so the web card and a future iOS button tell the same
tale. Web: a Check-in card at the top of the Dashboard (shown when a plan
exists), refreshing all app data on completion.

**Alternatives considered:** a background/cron check-in — rejected; it would
reintroduce scheduled Strava traffic (contrary to the ADR 0008/0011/0012
posture) and silent plan changes. The check-in is a deliberate weekly ritual,
kept one tap cheap.

Known behavior: with the deterministic (no-LLM) path the replan re-expands the
template at the week's target hours, so volume adaptation to poor compliance
is primarily an LLM-path behavior; the deterministic story still reports the
form flag and compliance honestly.

## Verification

- `test_weekly_checkin_composes_the_loop`: no-plan grace, degraded sync path,
  replan occurs, tests-due covers all three sports, key sessions + story lines
  present (157 total green; ruff; frontend build).
- Visual: seeded backend + generated plan → card click-through renders the
  full story (sync fallback, 0/4 compliance, fatigued TSB −28, replan with
  hours delta, three tests due, key sessions) — screenshot in PR #19.

## Follow-up

iOS check-in button hitting the same endpoint (tracked as a bean).
