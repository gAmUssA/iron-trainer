---
# iron-trainer-zxeb
title: Daily readiness push — the call is worthless if it is read after training
status: todo
type: feature
priority: normal
created_at: 2026-08-24T19:30:25Z
updated_at: 2026-08-24T19:55:40Z
parent: iron-trainer-flnw
---

Every project surveyed in `docs/research/ai-triathlon-coaches-landscape.md` delivers
chat-first — Telegram, WhatsApp, Claude chat — with dashboards second. Partly that is
because none of them have a UI, so it reads as a workaround.

The underlying point still holds, and it is the strongest one in the survey: **the
readiness call is worth nothing if it is read after training.** Today it waits for the
athlete to open the app, which on a 05:30 swim morning means it is read at lunchtime,
about a session that already happened.

We have an iOS app, so the native version of this is a morning push — cheaper than a
Telegram bot and better placed.

## Shape
One notification, early, carrying the call and the reason: "GO EASY — form is -30,
load ratio fine, but you're digging a hole." That text already exists in
`readiness/Readiness.java`; nothing delivers it.

## Constraints worth deciding up front
- **One a day, maximum.** A training app that pushes twice is uninstalled.
- Time must be configurable — 06:30 is wrong for anyone who swims at 05:30.
- Silence on a rest day is a feature, not a missed notification.
- Do NOT push a stale call: if the WHOOP sync has not run or the last one failed
  (`reconnect_required`), yesterday's recovery is not today's readiness. Say nothing
  rather than something confidently wrong — related to [[iron-trainer-00ww]].

## Todo
- [ ] APNs plumbing + device token registration (check what the iOS app already has)
- [ ] Scheduled job producing the daily call, guarded on data freshness
- [ ] Per-athlete send time in Settings
- [ ] Verify on a real device with a real 05:30 alarm, not the simulator


## Overlap check (should have been done before filing)

This was filed off the coach survey without first checking the in-progress epics, and
it lands squarely inside **[[iron-trainer-flnw]]** ("Daily Companion — the coach comes
to you… notifications, briefs") and **[[iron-trainer-03qt]]** ("iOS Companion &
Notifications… local reminders and morning briefs"). Re-parented under flnw.

**It is not a pure duplicate, and the difference is a real decision.** 03qt states the
posture explicitly:

> local reminders and morning briefs. **No servers, no background Strava — the device
> does the talking.**

This bean assumed a server-driven APNs push. Those are different architectures with
different consequences:

- **Local notification (03qt's posture):** the device schedules it from data it already
  holds. Works offline, no APNs plumbing, no server knowledge of when you wake — but it
  can only speak from the last sync the phone did, so on a morning where WHOOP has not
  synced it either says nothing or says something stale.
- **Server push (this bean):** the backend computes the call at send time from fresh
  data, and can stay silent when the data is not fresh. Costs APNs plumbing and means
  the server holds a schedule for each athlete.

Decide this deliberately rather than by whichever gets built first. The freshness
guard is the crux — see [[iron-trainer-00ww]].
