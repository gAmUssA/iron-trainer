# 0018 — Feel-vs-data check-in + local notifications (reminder & morning brief)

Date: 2026-07-15
Status: Accepted
Beans: iron-trainer-p526, iron-trainer-xijr

## Context

The weekly check-in was one-way: data in, story out. The Weekly Review coaching
pattern holds that the most useful line in a review is where how the athlete
*felt* and what the data *says* disagree — and that reviews must persist so
trends compound instead of resetting each week. Separately, the system only
spoke when opened; a coach that comes to you (morning brief, check-in nudge)
was the missing loop-closer — but our compliance posture forbids scheduled
backend work against Strava.

## Decision

**Feel-vs-data (p526)**
1. `checkin` table (Alembic `f7c9e1a3b5d7`): date, `inputs_json`,
   `story_json`, `readiness_json` per run. Persisted best-effort — a storage
   hiccup can't fail a check-in that already replanned.
2. Subjective inputs: `energy / sleep / body / stress`, ints 1–5 (**higher is
   always better** — "body 5" = fresh, "stress 5" = calm), plus a ≤280-char
   note. `sanitize_feel()` clamps and drops unknown keys server-side; POST
   `/api/plan/checkin` takes them in an optional JSON body (works with
   `?async=1`; inputs are captured in the job closure).
3. `_feel_vs_data_line()` reconciles feel against the readiness call:
   rough-but-green → "the fatigue is probably sleep or life stress";
   great-but-amber/red → "don't let a good day bait an overreach"; otherwise a
   quiet "aligned" line.
4. Compounding memory: `_fitness_summary()` now carries `recent_checkins`
   (date + feel + readiness call, newest first) and `todays_feel` into the
   week-replan LLM prompt, with instructions to favor recovery on persistent
   low sleep/stress and honor concrete notes.
5. UI: web CheckinCard gains a skippable 4×(1–5) + note form; iOS presents a
   `CheckinFeelSheet` before running (Skip is a first-class path).

**Notifications (xijr + p526 reminder)**
6. **Local notifications only** — `UNUserNotificationCenter`, no APNs, no
   server, no background fetch; consistent with the no-scheduled-Strava
   posture. Everything is computed from on-device data.
7. Weekly check-in reminder: repeating calendar trigger, Monday 08:00
   (Settings toggle).
8. Morning brief: next 7 days scheduled at 06:45 from the cached plan —
   today's session (title · minutes), its fuel line when present, race
   countdown; rest-day copy otherwise. Rescheduled on every plan refresh
   (`loadPlan` / `refreshPlanQuietly`), so content stays as fresh as the last
   sync; a stale plan degrades to slightly stale briefs, never to silence or
   background traffic. Settings toggle; permission denial flips the toggle
   back.

## Alternatives considered

- **APNs remote pushes** with server-side brief composition: real-time
  readiness in the brief, but requires push infra, key management, and a
  scheduled backend job — rejected under the compliance posture and MVP scope.
- **Free-form "how do you feel" text only**: unstructured, can't trend across
  weeks; the 1–5 quartet + note keeps it 10 seconds and comparable.
- **Mandatory inputs**: friction kills weekly habits; Skip is deliberate.

## Consequences

- Check-in history is now first-class data; future work (trends page, LLM
  season adaptation) can read it.
- The readiness call in the brief is *not* live (computed at schedule time
  would be stale) — the brief drives the open, the Today banner delivers the
  live call.
- 5 new backend tests (191 total). Verified live: two check-ins with opposing
  feel produce the two disagreement lines; rows persisted; iOS sheet →
  job body → story line confirmed in the simulator.
