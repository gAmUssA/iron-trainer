---
# iron-trainer-xijr
title: 'Morning brief: today''s session + readiness + countdown push'
status: in-progress
type: feature
priority: normal
created_at: 2026-07-14T20:11:12Z
updated_at: 2026-07-15T02:14:57Z
blocked_by:
    - iron-trainer-vhef
---

As an athlete I want a short morning brief on my phone — today's planned session, my readiness call, race countdown, one fuel/focus line — built entirely from already-synced local data (no scheduled Strava traffic).

## Summary of Changes

Morning brief as LOCAL notifications (no APNs/server/background fetch, consistent with compliance posture): next 7 days scheduled at 6:45 from the cached plan — today's session + fuel line + race countdown, rest-day copy otherwise. Rescheduled on every plan refresh (loadPlan/refreshPlanQuietly). Settings toggle with permission handling. Shares Notifications.swift with the p526 reminder. ADR 0018. Note: readiness call intentionally not in the brief (would be stale at schedule time) — the brief drives the open, the Today banner delivers the live call.
