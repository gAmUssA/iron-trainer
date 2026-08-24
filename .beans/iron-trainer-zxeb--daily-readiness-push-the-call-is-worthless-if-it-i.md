---
# iron-trainer-zxeb
title: Daily readiness push — the call is worthless if it is read after training
status: todo
type: feature
priority: normal
created_at: 2026-08-24T19:30:25Z
updated_at: 2026-08-24T19:30:25Z
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
