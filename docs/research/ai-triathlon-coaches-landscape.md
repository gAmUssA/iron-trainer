# Open-source AI triathlon coaches — what's worth borrowing

Source: a Meta AI digest, "GitHub Is Full of AI Triathlon Coaches Now" (2026 landscape
of open-source AI triathlon coaches syncing Strava + WHOOP).
Reviewed 2026-08-24 against this codebase.

---

## Start here: the source's central claim is wrong

> "Whoop doesn't have a public API, so most projects use manual CSV export or
> unofficial MCP servers."

**WHOOP has an official public developer API, and we shipped against it this week.**
Registered app, OAuth 2.0, documented rate limits (100 req/min, 10,000/day), v2
endpoints under `/developer/v2/`. See `backend-v2/.../whoop/WhoopApi.java` and bean
`4a6s`.

This is not a nitpick — it reframes the whole comparison. Every project in the digest
is working from CSV exports the athlete downloads by hand and emails to themselves.
We do that too, but only for **journal entries**, which genuinely have no API
endpoint. Everything else is live.

Treat the rest of the digest as a snapshot of what people built *before* the API was
easy to reach, and weight its recommendations accordingly.

---

## What they have that we already have

Almost all of it. Listing this explicitly so nobody re-derives it:

| Capability | Their projects | Us |
|---|---|---|
| Strava activities → training load | yes | yes |
| WHOOP recovery / HRV / sleep | CSV export | **live API** + ZIP for journal |
| CTL / ATL / TSB | yes | `metrics/` |
| ACWR safety guardrail | called out as their key safety feature | `readiness/Readiness.java` — with graded thresholds and plain-English reasons |
| Race readiness score | one project | `dashboards/RaceReadiness.java`, incl. power→race-intensity scaling |
| LLM plan generation w/ real data | yes | `plan/` + langchain4j |
| Deterministic fallback, no AI key | not mentioned | yes — self-host default |
| Apple Health ingestion | **none of them** | yes |
| TrainingPeaks / Zwift export | none | yes |
| iOS app | none | yes |

The digest's "what's common in 2026" list — dual data source, function-calling LLM,
ACWR monitoring, hard guardrails — describes what we already shipped.

---

## Worth borrowing, ranked

### 1. 80/20 intensity distribution — the one clear gap

Two of their projects compute weekly polarised-training distribution. **We do not.**
`zones/HrZones.java` defines the bands, but nothing computes time-in-zone per activity
or aggregates it weekly — I checked (`timeInZone`, `zoneMinutes`: no matches).

Why it matters more than most metrics for a 70.3 build: the single most common
amateur error is the junk-mile middle. CTL/ATL/TSB tell you *how much*; they say
nothing about *distribution*. An athlete can hold a perfect ramp rate and still train
80% at threshold.

We have every input already — activity HR streams, the zone bands, the athlete's
LTHR. This is aggregation, not new plumbing.

**Caveat worth stating up front:** Strava activity summaries may not carry HR
time-in-zone; if the streams API is needed per activity, this is a bigger job than it
looks. Verify before scoping.

### 2. Explicit gap reporting — "don't guess when data is missing"

The `triathlon-coach` skill's stated differentiator:

> Doesn't guess when data is missing — it reports gaps and degrades to HR/pace/RPE
> for load

We do this for *capability* (no AI key → deterministic planner) but not for *data*.
Today's WHOOP work is the argument for it: the export had 306 days where the API had
317, two-cycle days silently dropped a cycle, and none of that surfaced anywhere a
user could see. A "data completeness" panel — days covered per source, gaps, last
sync per integration — would have made this session's bugs visible in minutes rather
than requiring an offline diff of a ZIP.

Cheap, and it compounds: every future ingestion bug shows up here first.

### 3. Expose Iron Trainer as an MCP server

The digest's category 3 is people wiring Strava + WHOOP into Claude by hand via MCP,
so they can *ask questions* of their training data. We have far better data than any
of those ad-hoc setups and no way to converse with it.

An MCP server over the existing read endpoints (PMC, readiness, WHOOP cycles,
insights, plan) would make the whole dataset queryable from Claude Code — which is
where a lot of this project's work already happens.

Novel rather than catch-up: nobody in the digest exposes a curated dataset this way,
they each re-scrape the sources.

### 4. Push the daily call, don't wait to be visited

Every project in the digest delivers chat-first — Telegram, WhatsApp, Claude chat —
with dashboards second. That's partly because none of them *have* a UI, so it reads
as a workaround rather than a design choice.

But the underlying point holds: **the readiness call is worth nothing if you read it
after training.** We already have an iOS app, which makes the native version of this
a morning push notification, not a chat bot. Cheaper than Telegram and better placed.

---

## Deliberately not borrowing

- **Blood-test PDF parsing** (project 1). Genuinely novel, and out of scope for a
  race-prep tool. Reconsider only if the plan ever adapts on bloodwork.
- **Chat as the primary interface.** Their constraint, not ours.
- **intervals.icu export.** We already have TrainingPeaks and Zwift; a third target
  is only worth it if you personally use it.
- **GPT-4o / GPT-5 / LangGraph.** Framework churn with no user-visible gain — the
  langchain4j + Claude path already works.

---

## Beans filed

- `w04c` — 80/20 intensity distribution. Scoping resolved while filing: `Activity`
  stores `avgHr`/`maxHr` only, no streams, so session-level classification from
  `intensityFactor` is buildable today and minute-level time-in-zone is a much larger
  job. Do the cheap one first.
- `00ww` — data completeness: report gaps instead of interpolating over them
- `nruj` — read-only MCP server over the existing read API
- `zxeb` — daily readiness push (iOS), guarded on data freshness

## One correction to carry back

If this digest gets shared anywhere, the WHOOP API claim should be corrected. It is
the kind of error that sends people to build CSV import pipelines they do not need.
