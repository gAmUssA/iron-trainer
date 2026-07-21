---
# iron-trainer-90iu
title: NightAssembler — sleep-stage night assembly (per-source, sessionize, union)
status: todo
type: task
created_at: 2026-07-21T15:41:54Z
updated_at: 2026-07-21T15:41:54Z
parent: iron-trainer-yrsz
---

The part HAE did for us. Window prev-day 15:00 → today 15:00; group by source bundle id; NEVER merge stages across sources (iPhone inBed + Watch + Garmin double-count). Pick one winning source/night (user override → longest-asleep stage-writer). Sessionize with <2h gap merge; union overlapping intervals per stage before summing (Garmin re-syncs overlap). totalSleep = core+deep+rem(+unspecified), inBed excluded. Key by (source, wake-up date). Garmin 'light' ≈ Apple asleepCore — verify raw values on-device (5-min debug query).
