---
# iron-trainer-gcuv
title: Scheduled WHOOP sync is not safe across multiple instances
status: todo
type: task
priority: normal
created_at: 2026-08-21T14:22:39Z
updated_at: 2026-08-21T14:22:39Z
---

BootUI's Quarkus advisor (QA-SCH-001) flags it, and it lands directly on the WHOOP
daily sync just built in bean 4a6s:

> `@Scheduled` methods run on every instance; without a clustered scheduler each
> replica fires the job, causing duplicate work in a scaled-out deployment.

## Why it matters more than "duplicate work"

`WhoopSyncScheduler` fires at 10:00. On two replicas, both fire — and both call
`WhoopTokens.validAccessToken`, which may refresh. WHOOP refresh tokens are
**single-use and rotate**, and WHOOP documents that concurrent refreshes fail. So
the second replica's refresh is rejected, and the athlete can be left holding a
spent token needing a manual reconnect.

`WhoopTokens` guards this with `synchronized`, which is explicitly documented there
as sufficient ONLY within a single process. This bean is the other half of that
note: the guard is correct today because Railway runs one instance, and it silently
stops being correct the moment that changes.

The same applies to JobRunner's same-kind block — it is in-process too.

## Not urgent, but make it a deliberate decision
Currently latent: production is single-instance. The risk is that scaling out looks
like a pure ops change while actually breaking token rotation.

## Options
1. **Document single-instance as a constraint** and assert it — cheapest, honest.
2. **Quartz clustered** (`quarkus.quartz.clustered=true`, JDBC store, Flyway schema)
   — the advisor's suggestion; real work, and only the scheduler half.
3. **A DB advisory lock around the sync** — covers scheduler AND manual sync across
   instances, and is closer to the actual invariant (one WHOOP sync per athlete at
   a time) than "one scheduler firing".

Option 3 is probably right if we ever scale, because the token-rotation race is
per-athlete, not per-schedule.

## Todo
- [ ] Decide: constrain to one instance, or make the sync safe across instances
- [ ] Whichever way, make it explicit rather than accidental — a comment in
      railway.toml or an assertion at boot
