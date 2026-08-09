---
# iron-trainer-x78x
title: Migrate String timestamp columns to timestamptz/Instant
status: todo
type: feature
priority: deferred
created_at: 2026-08-08T23:24:43Z
updated_at: 2026-08-08T23:24:43Z
parent: iron-trainer-y2yz
---

Every entity stores ISO timestamps/dates as character varying to mirror the Python SQLModel (Activity.startDate, *.createdAt/updatedAt, Job.*, MetricDaily.date, DailyRecovery.*, Whoop*, etc.; V1__baseline.sql). util/Iso.java exists only to parse them back; range windows work by LEXICOGRAPHIC compare (PmcResource:39, AdminHealthResource:35/61, PlannedWorkout:93, RacesResource:50).

## Why DEFER (HIGH risk, LOW payoff)
ISO-8601-UTC strings already sort = chronologically, so nothing is broken. Migration =
- [ ] Flyway ALTER COLUMN ... TYPE timestamptz USING ...::timestamptz on LIVE prod rows
- [ ] entity retype to Instant/LocalDate
- [ ] a Jackson serializer PINNED to the current .SSSSSS+00:00 wire string (else iOS breaks — Jackson would emit epoch millis or different precision)
- [ ] rewrite every lexicographic range query as native comparison
- [ ] delete util/Iso.java
TRAP: do NOT change PyJson.utcNowIso's byte format while columns stay varchar — '.'(0x2E) vs 'Z'(0x5A) makes new/old rows interleave wrong.

## Verdict
Only pursue if a concrete timezone/date bug forces it. L effort, HIGH risk.
