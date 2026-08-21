---
# iron-trainer-si52
title: WHOOP connect UI + /api/whoop/status (API sync has no UI at all)
status: in-progress
type: task
priority: high
created_at: 2026-08-21T15:07:08Z
updated_at: 2026-08-21T15:16:29Z
parent: iron-trainer-4a6s
---

The API sync from 4a6s is complete and live-verified, but it is **reachable only by
typing a URL**. Nothing in `frontend/src` references any WHOOP OAuth endpoint —
`api.ts` has `whoopCycles`, `whoopInsights`, `whoopAnalyze` and `importWhoop`, and
that is all. To connect, you navigate to `/api/whoop/connect` by hand.

`WhoopView.tsx` already owns the right surface: it opens with an "Upload WHOOP
export (.zip)" block. The connection controls belong next to it, because the two are
alternatives to each other and the user is choosing between them.

## The backend gap that blocks the UI

**There is no `/api/whoop/status`.** The resource exposes import, cycles, insights,
insights/analyze, connect, callback, sync, disconnect — and no way to ask "am I
connected, and when did it last run?" Every piece of UI below needs that answer
before it can render, so this endpoint comes first.

It should return enough to drive the whole panel without a second call:
- `configured` — are `WHOOP_CLIENT_ID`/`SECRET` even set on this deployment? A
  self-hoster with no credentials should see the ZIP path and no dead Connect
  button. This is `WhoopOAuth.configured()`, already there.
- `connected` — does this athlete have a refresh token
- `lastSyncAt` and the last result (written/skipped), so the panel can say something
  truer than "connected"
- `whoopUserId`, or at least whether it is set

## The UI

- **Not configured** → no connect control at all, just the ZIP uploader.
- **Configured, not connected** → "Connect WHOOP" button → `/api/whoop/connect`.
- **Connected** → last-sync line, "Sync now", "Disconnect".

`?whoop_connected=1` already comes back on the callback redirect (confirmed in the
live test) — the view should consume it, show a confirmation, and strip it from the
URL so a refresh does not re-announce.

## The state that will actually bite

**Reconnect-required.** `WhoopTokens` throws a deliberate **409** when the refresh
token is spent, and the daily job logs it and moves on — so the failure is invisible
to the user, recurs every morning at 10:00, and the data silently stops updating
while the UI still says "connected". That is the one state worth designing for
rather than discovering. `/api/whoop/status` should distinguish it, and the panel
should say "reconnect needed", not "connected".

## Todo
- [x] `GET /api/whoop/status` — configured / connected / reconnect-required / last sync
- [x] Add `whoopStatus`, `whoopSync`, `whoopDisconnect` to `api.ts`
- [x] Connection panel in `WhoopView.tsx` alongside the ZIP uploader
- [x] Consume and strip `?whoop_connected=1`
- [x] Surface reconnect-required distinctly from connected
- [x] Disconnect should confirm before firing — it drops the token, and reconnecting
      costs a round trip through WHOOP
