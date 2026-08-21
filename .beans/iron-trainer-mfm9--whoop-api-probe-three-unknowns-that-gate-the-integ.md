---
# iron-trainer-mfm9
title: 'WHOOP API probe: three unknowns that gate the integration'
status: completed
type: task
priority: high
created_at: 2026-08-21T02:39:40Z
updated_at: 2026-08-21T20:24:09Z
blocking:
    - iron-trainer-4a6s
---

30 minutes, no code. Three unknowns gate the whole WHOOP live-sync design (bean 4a6s);
answering them is cheap and every one of them changes the plan if it goes the wrong way.

## 1. Is `http://localhost:PORT/...` an acceptable redirect URI? — HIGHEST IMPACT

The OAuth docs only ever show `https://whoop.com/example/redirect` and the custom scheme
`whoop://example/redirect`, and require the request URI to match the dashboard entry
exactly. Nothing says localhost is allowed; nothing says it is forbidden.

If it is NOT allowed, self-hosters cannot do a normal OAuth round trip at all, because a
laptop has no public callback URL. Fallback with zero infrastructure: register an https
URL that never resolves, let the browser land on a dead page, and have the user paste the
address bar contents into a box — the backend pulls `code` and `state` out of it. Ugly,
but it works offline and needs no tunnel.

Check: register an app at developer-dashboard.whoop.com and try to save a localhost
redirect URI.

## 2. How far back does the API serve history?

Docs are SILENT — not permissive, silent. The `start` parameter says only that omitting
it "will not filter cycles by a minimum time", which is a statement about filtering, not
retention.

Check: `GET /v2/cycle?start=2015-01-01T00:00:00.000Z&limit=25`, page to the end via
`next_token`, and compare the oldest cycle returned against the oldest row the ZIP import
already produced.

Note this does NOT decide whether the ZIP stays — see the journal finding in 4a6s. It
decides only whether the ZIP remains the deep-history source or becomes journal-only.

## 3. Does a sleep's `cycle_id` name the cycle that sleep OPENS?

The single most load-bearing assumption in the integration plan, and it is INFERRED
rather than cited — deduced from `WhoopArchive`'s wake → cycle-end → cycle-start fallback
ordering, which only makes sense if cycles run sleep-onset to sleep-onset.

If it is off by one, every overlapping date disagrees between ZIP and API and you get two
rows per physiological day.

Check: for one known night, compare `sleep.cycle_id` against the cycle whose `start`
precedes that sleep. (The Phase 2 shadow-mode diff in 4a6s also catches this for free —
this is just the cheaper way to find out first.)

## Todo
- [ ] Register a WHOOP app; record whether localhost redirect URIs are accepted
- [ ] Probe historical reach with the 2015 start date
- [ ] Verify the sleep -> cycle association direction
- [ ] Write the three answers into 4a6s so the implementation is not blocked on guesses


## ANSWERED — all three, 2026-08-21

### 1. Is `http://localhost:PORT/...` an acceptable redirect URI? — YES

The dev app registered and ran the entire OAuth round trip on
`http://localhost:8080/api/whoop/callback`. WHOOP's dashboard form validated it without
complaint; the only fields it flagged were Privacy Policy and Contact.

**The fallback this bean designed is unnecessary.** Self-hosters can do a normal OAuth
round trip, so the "register a dead https URL and have the user paste the address bar
into a box" scheme should not be built. That was the highest-impact unknown and it
resolved the cheap way.

### 2. How far back does the API serve history? — AT LEAST 5 YEARS

A full backfill returned `{"cycles":1522,"written":1426,"skipped":96,"from":"2021-08-21"}`.

Partially answered, and worth being precise: 5 years is where **our own**
`irontrainer.history-years` stops, not where WHOOP does. The export holds 2213 cycles
over 2085 days (~5.7 years), so WHOOP retains more than we asked for. The true ceiling
is still untested — but the practical question ("is the API limited to a short rolling
window?") is settled: it is not.

### 3. Does a sleep's `cycle_id` name the cycle that sleep OPENS? — YES, the join is correct

The load-bearing inference holds. Proven by comparison rather than by reading docs:
parsed the export offline and compared per-date against the API rows in production.

**If the join were off by one, EVERY overlapping date would disagree.** Instead ~94%
match exactly (modulo the export's lower precision), and the disagreements are not
shifted-by-a-day duplicates — they are days carrying two genuine cycles, where the
`(athlete_id, date)` key can only keep one.

So the feared failure mode does not exist. A different one does, and it is tracked
separately in bean 80i2 with the fix.

## Consequences
- The paste-the-URL OAuth fallback: do not build (see 1)
- The export ZIP stays as the journal source only — the API covers history (see 2)
- The shadow-mode diff planned in 4a6s is no longer needed to validate the join; the
  offline comparison did it and is repeatable as a script (see 3)
