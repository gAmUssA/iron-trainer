# ADR 0068 — iOS: auto-load the plan at launch, with an offline cache

**Status:** Accepted · 2026-08-16 · bean `5gep`

## Context

The app opened to `ContentUnavailableView("No workout yet")` on every cold start,
even for a signed-in athlete with an active plan. `ImportModel.state` began `.empty`
and nothing fetched until the user tapped **Load my plan**, hit the toolbar refresh,
or arrived through a pairing deep link. The Today view — the reason to open the app
before a session — was one tap away every single time, and unavailable with no
network at all.

Two separate problems hide in "load it automatically":

1. **Latency/availability.** Even with an automatic fetch, launch would show a
   spinner until the network answered, and show *nothing* offline.
2. **Authority.** A load nobody asked for has obligations a button press does not.
   It must not overwrite what the user is looking at, and it must not strand them
   in a full-screen error for an action they never took.

## Decisions

1. **Cache the plan on disk; restore it in `ImportModel.init`.** `TrainingPlan` is
   `(PlanFile.PlanMeta?, [ItwWorkout])` and both members were already `Codable`, so
   the entire cost of an offline Today view was adding `Codable` to one struct plus
   a ~40-line `PlanCache`. Restoring in `init` rather than a `.task` is deliberate:
   a `.task` restore flashes the empty state on every launch. It is a few-KB
   synchronous read, paid once, to remove a visible flicker.

2. **Application Support, excluded from backup — not Caches.** The plan is
   server-regenerable, which argues for Caches; but the whole point of persisting it
   is surviving a launch with no connectivity, and iOS may purge Caches under storage
   pressure at exactly that moment. Excluded from backup for the regenerable reason.

3. **`autoLoadPlan` is a separate method from `loadPlan`, not a flag.** They differ
   in behaviour, not degree:

   | | `loadPlan` (user) | `autoLoadPlan` (automatic) |
   |---|---|---|
   | Spinner | always | only with nothing to show |
   | Failure, plan on screen | n/a | silent, keeps the plan |
   | Failure, nothing on screen | `.failed` error screen | `.empty` + a reason |
   | User previewing a `.itw` | n/a | does nothing |

4. **`isAutoReplaceable` gates the automatic path.** `.empty`, `.loading` and
   `.loadedPlan` are replaceable; `.loaded` (a previewed workout), `.scheduled` and
   `.failed` are the user's own context and are left alone. This matters at launch:
   a `.itw` opened via `onOpenURL` races the launch fetch, and being yanked out of
   the file you just opened is worse than a slow load. Checked both before starting
   and after the response lands.

5. **A generation counter (`planLoadGen`) makes the last load win.** Launch,
   foreground and manual refresh can overlap; a slow superseded response must not
   overwrite a newer one. Same pattern the existing `writeGen` uses for widget
   snapshots.

6. **All plan adoption funnels through one `adopt(_:from:)`** — state, cache, widget
   snapshot, morning briefs. Three call sites fetch plans (`loadPlan`,
   `autoLoadPlan`, `refreshPlanQuietly`); without a single path the cache would
   silently drift out of sync with the screen.

7. **Refresh on foreground, not just launch.** A phone backgrounded overnight would
   otherwise still show yesterday as "today", which defeats the goal. Gated on the
   launch load having run, because a cold start fires both `.task` and the `.active`
   transition.

8. **Sign-out clears the cache.** Now that a plan survives on disk and auto-restores,
   not clearing it would show one athlete's plan to whoever pairs the device next.

## Consequences

- Today is available at launch, instantly, and offline. The **Load my plan** button
  remains for the genuine empty case and as a manual retry.
- An empty plan from the server is treated as information, not failure: the empty
  state says "generate one in the web app" and the stale cache is dropped rather
  than left showing a plan the server no longer has.
- One extra `GET /api/export/plan.itw` per foreground. It is a small authenticated
  read and it already ran on every manual refresh; if it ever matters, the obvious
  lever is a minimum interval between automatic refreshes.
- `ImportModel.init` now touches the filesystem, which makes it slightly less inert
  in tests — hence `PlanCache.clear()` in the test fixtures.

## Alternatives rejected

- **Auto-load with no cache** — the literal request, but it only moves the wait from
  a tap to a spinner, and still shows nothing offline. Not "always available".
- **Reuse the widget's `SharedStore` snapshot** — already on disk, but it is a
  reduced 7-day render model, not a `TrainingPlan`; Today would have to be rebuilt
  against a lossy shape.
- **Restore in `.task` instead of `init`** — avoids main-thread I/O, costs a visible
  empty-state flash at every launch. Wrong trade for a few KB.
- **Refresh on a timer** — more moving parts than foregrounding, which is when a
  phone actually gets looked at.

## Verification

13 new unit tests. `PlanCacheTests` (8): round-trip, absent cache, clear,
**corrupt cache degrades to "no cache" rather than trapping**, init restores,
init stays empty without a cache, an empty cached plan is ignored, forget clears
screen and disk.

`AutoLoadPlanTests` (5), against a stubbed `URLProtocol`: the plan appears with no
user action and seeds the cache; offline with nothing cached falls back to `.empty`
**with a reason instead of an error screen**; offline with a cached plan keeps it
and stays silent; an opened `.itw` is not clobbered by the launch load; an empty
server plan explains itself and clears the stale cache.

Full iOS suite **29 passed / 0 failed** (was 16). Builds clean for iOS Simulator.

**Not verified:** end-to-end launch against a live paired server on a device — the
network path is covered only by the stub. Worth one manual pass on the next
TestFlight build, specifically a cold start in airplane mode.
