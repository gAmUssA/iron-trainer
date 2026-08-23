---
# iron-trainer-80i2
title: WHOOP ZIP and API disagree on 6% of days — (athlete,date) drops a cycle
status: completed
type: bug
priority: high
created_at: 2026-08-21T19:05:55Z
updated_at: 2026-08-23T17:26:37Z
parent: iron-trainer-ids6
---

Asked to prove that export-ZIP rows and API rows carry identical data. They do not,
and the interesting half is not a mapping bug.

Method: parsed `my_whoop_data_2026_07_29.zip` offline, replicated `WhoopArchive`'s
derivation exactly, and compared per-date against the API-sourced rows now in
production.

## Two kinds of difference

**1. Precision — benign.** The export truncates and quantises; the API does not.

| field | API | ZIP |
|---|---|---|
| hrv_rmssd_ms | 54.8577 | 54 |
| sleep_efficiency_pct | 92.7387 | 92 |
| day_strain | 4.0649 | 4.1 |
| asleep_h | 6.9253 | 6.9167 (whole minutes) |

Same measurement, less resolution. The API is strictly better and `V11`'s
api-over-zip precedence is right for this.

Also settled a hypothesis that turned out WRONG: `asleepH` is computed differently on
each side — the ZIP reads WHOOP's `Asleep duration (min)`, the API sums light + SWS +
REM. Across **1,819 nights in the export they are equal EXACTLY, every time**, zero
exceptions. The two formulas agree by construction. Do not "fix" this.

**2. `(athlete_id, date)` cannot represent a day with two cycles — and 6% of days
have two.**

```
total cycles in export : 2213
distinct derived days  : 2085
COLLISION days         : 128   (6.14%)
cycles dropped         : 128
  spanning a timezone change : 21
  same timezone              : 107
```

The primary key silently keeps one cycle and discards the other, and **which one
survives depends on ingest order** — so ZIP and API can retain different cycles for
the same date. Confirmed on 2025-10-07, a transatlantic travel day:

```
start 2025-10-07 00:53  UTC+02:00  recovery=30  energy=3098  sleep_perf=74
start 2025-10-07 19:47  UTC-04:00  recovery=10  energy=2969  sleep_perf=37
```

Production holds recovery=10 (API); the ZIP import had stored 30. Every field on that
date differs, and not by rounding.

Worse examples in the same set — 2026-04-13 recovery `1` vs `55`, 2025-12-01 `10` vs
`75`. Readiness numbers that feed the training model, differing by more than the
entire scale's useful range.

Note the majority (107 of 128) are NOT timezone changes, so "travel day" is only part
of it — WHOOP records two cycles in one local day fairly often.

## What this means

- A "Full re-sync" does not just refresh data; on ~6% of days it CHANGES which cycle
  is displayed. Not worse, but different, and non-deterministic in that it depends on
  which source ran last.
- The overlay charts, bedtime consistency and any correlation work silently use
  whichever cycle won.

## Todo
- [ ] Decide the intended semantics for a two-cycle day: keep the primary sleep cycle,
      keep the longest, or keep both
- [ ] If both: `(athlete_id, date)` has to go — likely `(athlete_id, whoop_cycle_id)`
      with date as an indexed column. That is a real migration and touches every
      reader.
- [ ] If one: make the CHOICE explicit and identical in both ingest paths, rather
      than letting last-writer-wins decide
- [ ] Until then, do not present api-vs-zip precedence as "the API is more accurate" —
      on these days it is merely more recent


## CORRECTION — the blast radius is 0.5%, not 6%

The 128 collision days overstate it. Only **10 of them have BOTH cycles scored**:

```
collision days                  : 128
days where BOTH cycles scored   :  10
later cycle median duration     : 26.5h (not naps — only 6 are nap-like)
```

On the other 118, one cycle carries no recovery score, and the upsert's existing
null-guard ("a null must not blank an existing value") already makes ingest order
irrelevant — the scored values survive whichever source writes last. Those days need
no action.

So the genuinely ambiguous set is **10 days out of 2085 (0.5%)**.

## DECISION

Keep `api` over `zip`. Add a deterministic tie-break. Do NOT migrate the key.

- ~94% one cycle, identical but for precision → API strictly better, nothing to do
- 5.6% two cycles, one unscored → null-guard already resolves it, nothing to do
- 0.5% (10 days) both scored → **earliest local wake wins**

Earliest local wake is the morning recovery, which is what WHOOP's own app presents
as "today's recovery"; the later cycle on a travel day is the post-flight one
(2025-10-07: morning 30 vs post-flight 10). Worth noting the ZIP import ALREADY
picks the morning cycle and the API full re-sync overwrote it with the worse one —
so today's behaviour is not merely non-deterministic, it actively degrades those days.

Not migrating to `(athlete_id, whoop_cycle_id)`: correct, but it touches every reader
— dashboards, insights, bedtime consistency, overlays — to fix 10 days on which
nothing in the app wants to show two cycles anyway. Revisit only if something needs
per-cycle granularity.

Once both paths pick the same cycle, api-over-zip is unambiguously right, because the
only remaining difference is precision — where the API genuinely wins.

## Todo (supersedes the list above)
- [x] Applied as WhoopCycle.dedupeByDate, called by both paths (scored beats unscored, then earliest cycleStart)
- [x] Verified: reproduces 2085 days exactly and keeps the morning cycle on all 10
- [x] Now accurate — with both paths agreeing on WHICH cycle, the only difference left is precision


## Review of #131 — deduping within a fetch was not enough

Copilot caught that `dedupeByDate` only picks a winner among rows in the CURRENT
response, while the upsert then judges against the stored row by `apiUpdatedAt`. Two
holes, both real:

1. **A re-sync could never REPAIR an already-wrong day.** The correct morning cycle
   normally carries the OLDER `updated_at`, so the staleness guard rejected it as
   stale. That is precisely the state production is in for the 10 ambiguous dates —
   the fix would have prevented future corruption while leaving the existing damage
   permanent.
2. **A bounded window could re-corrupt a correct day.** If the daily window starts
   after the morning cycle, WHOOP returns only the later one; dedupe passes the single
   row through, its `updated_at` is newer, and it overwrites a correct row.

Fixed by applying the same judgement in `upsert` against the stored row.
`WhoopCycle.sameCycleAs` distinguishes an update from a different cycle competing for
the date (`whoopCycleId` when both have it, else `cycleStart` — the ZIP sets no id).
Different cycle -> the tie-break decides and the timestamp rule is bypassed, because
comparing the `updated_at` of two DIFFERENT cycles is meaningless.

Replacement is a full overwrite, not the null-skipping merge: keeping the loser's
values where the winner has none stitches a row out of both cycles — the morning
cycle's recovery beside the evening cycle's SpO2, a day that never happened.

Both cases have tests, verified to fail without the check:
`expected: <30.0> but was: <10.0>` and `expected: <75.0> but was: <10.0>`.

**Consequence worth noting: the 10 bad days in production self-heal on the next full
re-sync.** Before this they would have stayed wrong forever.


## Second review pass on #131 — the ZIP writer bypassed the rule entirely

Two suppressed findings, both correct.

**The ZIP importer does not use `upsert`.** It has its own delete+batch-insert path
(a per-row merge SELECTs each PK — ~27k round trips, which blew the 60s transaction
timeout in the 2026-08-05 prod incident). That path did
`cycleByDate.keySet().removeAll(apiOwned)`: every api-owned date dropped
unconditionally, no cycle comparison. So a bounded API window could store the LATER
cycle and no export could ever displace it — the stored winner still depended on which
source ran first, which is the entire defect this bean exists to remove.

Now applies the same `sameCycleAs`/`preferredOver` decision per date: same cycle keeps
the API row (higher precision, which is what api-over-zip is FOR), different cycle goes
to the tie-break.

Implementation note worth keeping: the stored rows must be read as a **scalar
projection**, not `find().list()`. Loading them as managed entities puts them in the
persistence context and the batch persist then dies with
`NonUniqueObjectException — a different object with the same identifier` on exactly
the dates the export wins. Cost me a 500 before I saw it.

**docs/deploy.md verification advice was unsound** — and in the section about a
service that silently serves the old image on a failed boot, which is worse than
merely wrong. It said absence of the `Migrating schema` line means "already applied".
It equally means Flyway never started, the container never booted, or logs were
unavailable. Rewritten to require positive evidence: either the migration line, or
`Schema "public" is up to date` together with a matching
`Current version of schema "public": N`.

Test verified to fail against the old behaviour: `expected: <67.0> but was: <9.0>`.
