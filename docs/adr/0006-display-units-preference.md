# 0006 — Display units preference (miles/km + human-readable durations)

- **Status:** Accepted
- **Date:** 2026-06-27
- **Deciders:** Viktor + Claude
- **Builds on:** [0005](0005-fitness-tests-library.md)

## Context

The fitness-test forms ([0005](0005-fitness-tests-library.md)) collected and showed
distance in **raw meters** and duration in **raw seconds** (e.g. a Strava-prefilled
run appeared as `9518 m` / `3069 s`). That's unreadable — athletes think in
miles/km and `m:ss`/`h:mm:ss`. We need a **units option** (miles vs km) and
human-readable durations, without disturbing the backend's canonical storage.

## Decision

Keep the **backend canonical** (meters, seconds, sec/km, sec/100m) and convert
**only at the frontend boundary**. Add a **display-units preference** (`mi`/`km`),
persisted in `localStorage` and exposed app-wide via a small React context
(`units.tsx`), mirroring the existing `theme.tsx` pattern, with a header toggle next
to the theme switch. Default: `mi` for US/UK locales, else `km`.

Apply it where distance/duration surface:
- **Tests form** — distance fields render in the chosen unit (label `(mi)`/`(km)`),
  duration fields render/parse as `h:mm:ss`/`m:ss`; values convert to meters/seconds
  on submit and back when prefilling from Strava. Toggling the unit re-converts any
  distance already in the form.
- **Computed/threshold pace** — run pace shows `/mi` or `/km` (`paceInUnit`); swim CSS
  stays `/100m` (a pool constant, unit-independent).
- **Trends** — the "Run pace" sparkline honors the same unit.

Conversions live in `units.tsx` (`metersToUnit`/`unitToMeters`, `secsToHMS`/
`hmsToSecs`, `paceInUnit`).

## Alternatives considered

- **Store a units preference on the athlete (backend)** — rejected: it's a pure
  display choice; per-device localStorage (like theme) is simpler, instant, and needs
  no API/migration. Can be promoted to a server setting later if multi-device sync is
  wanted.
- **Convert in the backend / return values in display units** — rejected: keeping one
  canonical representation server-side avoids unit ambiguity in the API, tests, and
  the `.itw`/exports; the UI is the only place a human unit matters.
- **Imperial-everything (also pace, power)** — out of scope: power is W regardless;
  swim is per-100m by convention; only distance + run pace are unit-sensitive.

## Consequences

- (+) Readable, locale-appropriate forms; Strava prefill shows `8.28 mi` / `1:10:00`.
- (+) No backend/API/migration change; canonical units stay intact end-to-end.
- (−) Preference is per-device (localStorage), not synced across devices.
- (−) The Thresholds editor's run-pace field still shows raw `m:ss` (no /mi label) —
  left as-is to avoid converting stored thresholds; noted as a possible follow-up.

## Implementation notes

`frontend/src/units.tsx` (context + conversions), `main.tsx` (`UnitsProvider`),
`App.tsx` (header mi/km toggle), `components/TestsView.tsx` (unit-aware inputs +
toggle re-convert), `components/Dashboards.tsx` (TrendsChart run pace).

## Verification

`npm run build`; verified in-browser: header toggle; Run prefill shows `8.28 mi` +
`1:10:00`; toggling mi→km re-converts `8.28 → 13.32` and flips the label; Trends run
pace switches `/mi`↔`/km`. Frontend-only — no backend tests affected, no TestFlight
build needed.
