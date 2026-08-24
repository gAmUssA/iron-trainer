---
# iron-trainer-00ww
title: 'Data completeness: report gaps instead of quietly interpolating over them'
status: todo
type: feature
priority: normal
created_at: 2026-08-24T19:30:00Z
updated_at: 2026-08-24T19:30:00Z
---

Borrowed from the `triathlon-coach` skill's stated differentiator (see
`docs/research/ai-triathlon-coaches-landscape.md`):

> Doesn't guess when data is missing — it reports gaps and degrades to HR/pace/RPE

We do this for **capability** (no AI key falls back to the deterministic planner) but
not for **data**. There is nowhere in the app that answers "how complete is what you
are looking at".

## The case for it is this week's WHOOP work

Every one of these was invisible until someone went digging:

- the export held 306 days where the API held 317 in the same window
- 128 days carried two cycles and the `(athlete_id, date)` key silently kept one
- a 5-year backfill was re-fetching years already on disk
- 23 days have strain and energy but no sleep data at all

Finding the first two took parsing a ZIP offline and diffing it against production. A
completeness panel would have shown them in minutes.

## Shape
Per source — Strava, WHOOP, Apple Health — show days covered in the window, the gaps,
the last successful sync, and where a metric is derived rather than measured. The
point is not prettiness; it is that a missing day should be **visibly** missing
instead of quietly interpolated over.

## Todo
- [ ] Coverage query per source over a date window
- [ ] Panel (Trends or Settings — wherever it will actually be looked at)
- [ ] Mark derived-vs-measured values where the distinction changes interpretation
- [ ] Consider surfacing gaps in the AI plan prompt, so it stops reasoning confidently
      over weeks that are mostly absent
