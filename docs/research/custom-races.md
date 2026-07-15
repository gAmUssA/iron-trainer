# Custom races beyond triathlon — research (bean iron-trainer-wb53, 2026-07-15)

Full report in bean/agent transcript; condensed here. Verdict: architecture is
friendlier than expected — the adaptive loop (readiness, recovery, check-in,
jobs, reconcile, PMC, fitness tests, exports, iOS countdown) is ALREADY
event-agnostic (verified by grep). Tri-ness concentrates in ~6 spots where
"70.3" is a constant.

## Where 70.3 is hardcoded (evidence)

- repo.py:401 _CUTOFFS keyed 70.3/140.6; cutoffs_for() FALLS BACK TO 70.3 for
  any string — a "marathon" today gets swim/bike cutoffs stamped.
- template.py:16 SPORT_SPLIT {Swim .2, Bike .5, Run .3}; expand_week fixed
  6-session tri week; season copy says 70.3. Skeleton itself is generic.
- validator.py:14-23 TAPER_WEEKS=2, ABS_MAX_WEEK_HOURS=16, SESSION_CAPS_H
  (century rides would clamp to 4h).
- llm.py personas ("IRONMAN 70.3 coach"), sport enum closed, nutrition phase
  enum = 7 tri phases.
- dashboards.py LEG_DISTANCES 70.3/140.6 + 3-leg projection + fixed T1/T2.
- nutrition.compute_race_day_plan unconditional swim→T1→bike→T2→run timeline.
- RaceCard.tsx limited to 70.3/140.6. iOS is race-agnostic already.

## Industry patterns

TrainerRoad: event = date+discipline+A/B/C priority, plan built backwards
from last A race. Runna: custom run distance 5-50km, no goal-time required.
Periodization: marathon 4-5 runs/wk, 3wk taper, cap run 3h; century Bike~.9,
1-2wk taper, cap bike 5.5h; OWS 1wk taper + sighting skills; finish-goal =
1 quality, time-goal = 2 quality + race-pace segments.

## Design (additive)

- Race v2: event_type (tri|run|ride|swim|multisport) + legs_json ordered
  [{sport, distance_m}]; cutoffs optional (auto only for tri). Backfill stamps
  event_type=tri for 70.3/140.6 — 41 seeded races unchanged. Athlete gains
  goal(finish|time)+goal_time_s; A/B/C priority in P3.
- ONE EVENT_PROFILES dict (planning/event_profiles.py): sport_weights, taper
  weeks, brick flag, session caps, max week hours, week_pattern, quality count,
  persona — consumed by template + validator + LLM prompts. Validator
  constants become profile defaults; new rule: no Brick unless profile.brick.
- race_readiness iterates legs (per-sport projectors already exist at
  dashboards.py:162-186); transitions only when len(legs)>1; single finish
  cutoff for single-sport. Nutrition branches: single-sport = pre→race→post.
- Phasing: P1 run races end-to-end (~2-3d), P2 ride+swim (~1d), P3 custom
  multisport + multi-race season w/ athlete_race join table + A/B/C (~3-5d,
  own bean — only phase changing planner SHAPE not constants).
