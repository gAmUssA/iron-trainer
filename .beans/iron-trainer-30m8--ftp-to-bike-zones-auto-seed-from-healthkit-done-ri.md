---
# iron-trainer-30m8
title: 'FTP to bike zones: auto-seed from HealthKit done right'
status: todo
type: feature
priority: normal
created_at: 2026-07-21T07:08:51Z
updated_at: 2026-07-21T15:41:54Z
parent: iron-trainer-2f2c
---

Split out of mg1n after code review. mg1n CAPTURES Apple's cycling FTP estimate per day in daily_recovery.cycling_ftp_w but does NOT auto-seed Athlete.ftp / bike zones — doing it safely needs:
- Latest-by-timestamp FTP, not the daily mean (averaging stale 200 + fresh 260 -> 230 makes zones ~13% low). Needs sample timestamps in the parser.
- Seed guard bounds matching ProfileResource's validator (ftp in (0,1000]); a seeded 1000-2000W would block the next profile save (422) + build absurd zones.
- Delta-sync-safe source-of-truth: seeding Athlete.ftp + bumping updated_at makes iOS pull it down, could clobber a device-entered FTP. Decide policy (server authoritative? only-if-null + source flag? adopt UX?).
- Trust: Apple's cycling FTP estimate quality varies; consider seed-if-null + athlete-accept over silently driving zones.

## Todos
- [ ] Parser exposes latest-by-timestamp FTP
- [ ] Seed guard = profile validator bounds (0,1000]
- [ ] Source-of-truth + delta-sync policy
- [ ] Wire into PlanTargets bike zones + accept/adopt UX
- [ ] Test
