---
# iron-trainer-80i2
title: WHOOP ZIP and API disagree on 6% of days — (athlete,date) drops a cycle
status: completed
type: bug
priority: high
created_at: 2026-08-21T19:05:55Z
updated_at: 2026-08-21T20:24:18Z
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
