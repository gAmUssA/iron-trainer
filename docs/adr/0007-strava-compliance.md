# 0007 — Strava OAuth UX hardening + brand/policy compliance

- **Status:** Accepted
- **Date:** 2026-06-27
- **Deciders:** Viktor + Claude
- **Builds on:** [0002](0002-multi-user-login-with-strava.md)

## Context

A second athlete couldn't connect — the cause was **Strava's own** "403: Limit of
connected athletes exceeded" (new apps are capped at 1 athlete), not our allowlist
(verified correct). The capacity bump (→10) was requested via Strava's Developer
Program form, putting the app under Strava review. That prompted a pass over Strava's
[Brand Guidelines](https://developers.strava.com/guidelines/) and
[API Agreement](https://www.strava.com/legal/api_policy). The audit found: custom
(non-official) login buttons, no "Powered by Strava" attribution, raw JSON error
pages on OAuth failure, and **no disconnect/deauthorize or data-deletion** path.

## Decision

Implement the clearly-actionable items now; flag the heavier architectural/legal ones.

1. **OAuth UX hardening** — the callback no longer raises raw `HTTPException`; it
   **redirects to the SPA with `?strava_error=<code>`** (denied/`no_code`,
   `invalid_state`, `exchange_failed`, `not_allowed`), and the app shows a dismissible
   banner. Token exchange is wrapped in `try/except httpx.HTTPError`. A capacity hint
   is shown on the login screen (Strava's own limit-exceeded 403 renders on strava.com,
   so we can't always intercept it server-side).
2. **Official Strava branding** — official-style **"Connect with Strava"** button
   replaces the custom buttons; **"Powered by Strava"** in the app footer;
   **"View on Strava"** link on the connected-account row with the required treatment
   (#FC5200). Assets live in `frontend/public/strava/`.
3. **Disconnect + delete-my-data (§7.4 / §2.5)** — `POST /api/strava/disconnect` calls
   Strava `/oauth/deauthorize`, then purges the athlete's activities + `MetricDaily` +
   tokens (keeping the athlete row + manually-entered thresholds), and returns a
   deletion summary (the §2.5 written confirmation).

**Flagged, not changed (this pass):**
- **§5.3 (no Strava Data with the operation of an AI app)** — plan generation sends
  Strava-derived data to Claude. **Decision: flag only** — document the risk; do not
  change plan-gen yet; revisit after Strava responds (their review may force it).
- **§6.2 (no caching Strava data > 7 days)** — we retain activities indefinitely.
  Deferred: a purge would require reworking threshold inference + CTL windows to run
  off short-lived raw data.

Both are tracked in `docs/strava-compliance.md`.

## Alternatives considered

- **Raise HTTPException with friendlier text** (instead of redirecting) — rejected: a
  JSON error page on the API origin is a dead end for the user; redirecting back into
  the SPA lets us render an in-product banner and a retry.
- **Recreate vs use official brand artwork** — the brand kit is download-only (no
  fetchable URL). We ship faithful, brand-spec SVGs now and flag (in the assets README
  + this ADR) that the exact official files must be dropped in before public launch —
  better than the prior text buttons while keeping the swap a one-file change.
- **Delete the whole athlete row on disconnect** — rejected: the athlete's login and
  manually-entered thresholds are theirs, not Strava Data; we clear only Strava data +
  tokens + the Strava-sourced name.
- **Address §5.3 now by decoupling AI from Strava data** — deferred per decision; it's
  a real architecture change and may be moot depending on Strava's response.

## Consequences

- (+) OAuth failures (incl. the capacity case, when Strava redirects back) are legible.
- (+) Branding + attribution + disconnect/deletion bring the app materially closer to
  Strava's terms; users can self-serve data deletion.
- (−) Brand assets are recreations pending the official-file swap (flagged).
- (−) §5.3 and §6.2 remain open risks — documented, not resolved. The app's core
  "Strava → Claude plan" flow may need revisiting depending on Strava's position.
- Disconnect leaves the athlete logged in with their manual thresholds; re-connecting
  re-syncs from scratch.

## Implementation notes

Backend: `strava.deauthorize`, `repo.disconnect_strava`, `strava_router` callback
redirect helper `_redirect` + `POST /api/strava/disconnect`. Frontend:
`api.disconnect` + `stravaErrorMessage`, `App.tsx` (URL flag → banner + footer),
`LoginScreen.tsx` + `Setup.tsx` (official button, View-on-Strava, Disconnect),
`public/strava/*`. Docs: `docs/strava-compliance.md` (linked from `docs/deploy.md`).

## Verification

`tests/test_strava_compliance.py`: callback `error=access_denied` and exchange failure
**redirect** with `?strava_error=` (302/307, not 500/JSON); `disconnect` deletes
activities + `MetricDaily`, clears tokens, keeps the athlete + manual FTP. Updated
`test_auth.py` (rejection now redirects with `strava_error=not_allowed`). 74 backend
tests pass; ruff clean; frontend builds. In-browser: official Connect button, Powered
by Strava footer, View on Strava link, OAuth-error banner, and the disconnect flow
(curl: deleted 70 activities + 91 metric days). iOS unaffected.
