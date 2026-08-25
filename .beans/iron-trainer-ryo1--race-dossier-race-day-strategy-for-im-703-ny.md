---
# iron-trainer-ryo1
title: Race dossier + race-day strategy for IM 70.3 NY
status: todo
type: feature
priority: normal
created_at: 2026-07-14T20:11:12Z
updated_at: 2026-07-15T02:33:12Z
parent: iron-trainer-hdn5
---

As an athlete I want a researched course dossier for IRONMAN 70.3 NY (course, conditions, finisher warnings) and a race-day strategy built from my numbers: bike power cap as %FTP to protect the run, run pacing off the bike, carbs/fluid/sodium per hour with math shown and estimates labeled. Includes taper + race-week gameplan. Time this for late summer 2026.


## Draft plan — 2026-08-25 (32 days out)

`docs/race/70.3-new-york-2026-plan.md`, also published as a page for race-morning
reading: https://claude.ai/code/artifact/c72ddc8d-8c38-4fd7-bc64-5ccf423819e5

Built from live prod data: FTP 252 W @ 100 kg, LTHR 173, run threshold 5:23/km,
CSS 2:05/100 m, CTL 59.6 / TSB -12.3 / ACWR 0.64, projected 6:38:19 vs an 8:30 cutoff.

### The one place the plan overrides the app
`RaceReadiness` projects the bike at `RACE_IF = 0.78` (197 W). The plan prescribes
**0.72 (181 W)**, ceiling 0.75. At CTL 59.6 the durability to hold 0.78 for 3:45 and
still run is not there. The projection is a FITNESS ESTIMATE, not a prescription —
worth remembering if the readiness constant is ever reused as a target elsewhere.

### Course research changed the strategy
Marketed as flat and fast. Two independent sources report a "near constant steady
incline" from Jones Beach out to the Jericho Turnpike turnaround, done twice as an
out-and-back; the race guide says 750 ft of gain while a finisher's Garmin logged
2,000 ft. Wind is the dominant variable — 2023 had 25 mph sustained with 30+ gusts and
the swim was cut from 1.2 mi to 750 yd. Run is two loops on an unshaded boardwalk.

### Blocked on athlete input
`sweat_rate_l_h`, `gel_carb_g` and `gi_tolerance` are all null, so the fluid and sodium
figures are derived from body mass alone (0.8 L/h placeholder) and labelled as
estimates. For a 100 kg athlete the truth could be 0.6 or 1.4 L/h — at 1.4 the bike
finishes 2 L down and the run does not happen. A sweat-rate test protocol is in the
plan; it must run before taper, i.e. within ~10 days of 2026-08-25.

## Todo
- [x] Course dossier (conditions, incline, wind history, finisher warnings)
- [x] Bike power cap as %FTP, with the reasoning for overriding RACE_IF
- [x] Run pacing off the bike
- [x] Carbs/fluid/sodium with math shown and estimates labelled
- [x] Taper + race-week gameplan
- [ ] **Athlete action:** sweat-rate test, then set the three nutrition fields
- [ ] Regenerate the fuelling section once those are real numbers
- [ ] Post-race: decide whether any of this generalises into a feature, or stays a
      one-off document. Do NOT build the generator before the race.
