---
# iron-trainer-tzex
title: FTP → bike zones auto-seed from HealthKit (done right)
status: scrapped
type: feature
priority: normal
created_at: 2026-07-21T07:08:08Z
updated_at: 2026-07-21T07:38:59Z
parent: iron-trainer-udbc
---

Split out of mg1n after code review. mg1n now CAPTURES Apple's cycling FTP estimate per day in daily_recovery.cycling_ftp_w, but does NOT auto-seed Athlete.ftp / bike zones — doing that safely needs more than a naive seed:

- **Latest, not averaged**: within a day, take the latest-by-timestamp FTP sample, not the daily mean (averaging a stale 200 + fresh 260 → 230 makes zones ~13% low). Requires tracking sample timestamps in the parser.
- **Bounds match the profile validator**: ProfileResource validates ftp in (0, 1000]; a seeded 1000–2000 W value would then block the user's next profile save (422) and build absurd zones. Guard the seed to the SAME bounds.
- **Delta-sync-safe source of truth**: seeding Athlete.ftp + bumping updated_at makes the iOS delta-sync pull it to the device — could clobber a device-local FTP the user entered but hasn't pushed. Decide the policy (server authoritative? only-if-null + a 'source=healthkit' flag? adopt-UX?).
- **Trust question**: Apple's cycling FTP estimate quality varies; consider seed-if-null only + surfacing it for the athlete to accept, rather than silently driving zones.

## Todos
- [ ] Parser: expose latest-by-timestamp FTP (not the daily mean)
- [ ] Seed guard bounds = profile validator (0, 1000]
- [ ] Source-of-truth + delta-sync policy (avoid clobbering device-entered FTP)
- [ ] Wire into PlanTargets bike zones + an accept/adopt UX
- [ ] Test

## Scrapped 2026-07-21: duplicate of [[iron-trainer-30m8]] (created twice when the id didn't print). 30m8 is canonical (referenced in ADR 0046 + PR #89).
