---
# iron-trainer-w04c
title: 80/20 intensity distribution — nothing reports how hard training actually is
status: todo
type: feature
priority: normal
created_at: 2026-08-24T19:30:00Z
updated_at: 2026-08-24T19:30:00Z
---

CTL/ATL/TSB answer "how much"; nothing in the app answers "how hard, and how often".
An athlete can hold a textbook ramp rate and a healthy ACWR while training most of the
week in the threshold middle — the most common amateur 70.3 error, and currently
invisible on every chart we ship.

Two open-source AI coaches surveyed in `docs/research/ai-triathlon-coaches-landscape.md`
compute weekly polarised distribution. We do not.

## The scoping question that decides the whole shape

`Activity` stores **`avgHr` and `maxHr` only** — no HR streams, no time-in-zone
(verified: no `timeInZone` / `zoneMinutes` anywhere). `zones/HrZones.java` defines the
bands but nothing consumes them per activity.

So there are two very different jobs here, and the cheap one is probably the right one:

**A. Session-level classification — buildable from stored data today.**
Classify each session into easy / moderate / hard from `intensityFactor` (better
discriminator than `avgHr`, and already stored), then report the weekly split. This is
also close to how the polarisation literature actually classifies — by session intent,
not by minute. No new ingestion, no Strava rate-limit exposure.

**B. Minute-level time-in-zone — needs Strava streams.**
Per-activity stream fetches, new storage, and a rate-limited backfill for history.
More precise, considerably more machinery, and the precision may not change any
decision the athlete makes.

**Do A first and see whether B is ever missed.** Do not start with B because it sounds
more rigorous.

## Todo
- [ ] Decide the easy/moderate/hard thresholds on IF, and write down the rationale
- [ ] Weekly split on the Trends tab, with the target band shown for comparison
- [ ] Flag sustained middle-heavy weeks in the readiness reasons, where the athlete
      will actually see it
- [ ] Only then: evaluate whether minute-level (B) adds a decision anyone acts on
