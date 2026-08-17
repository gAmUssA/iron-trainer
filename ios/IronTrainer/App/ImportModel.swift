import Foundation
import WidgetKit

/// Drives the import → preview → schedule flow.
@MainActor
final class ImportModel: ObservableObject {
    enum State: Equatable {
        case empty
        case loading
        case loaded(ItwWorkout)
        case loadedPlan(TrainingPlan)   // a fetched plan → Today view
        case scheduled(String)          // human summary
        case failed(String)
    }

    @Published private(set) var state: State = .empty

    /// Why the automatic plan load came up empty, when it did. The user did not
    /// press anything, so a failure must explain itself in the empty state
    /// rather than take over the screen with an error.
    @Published private(set) var autoLoadNotice: String?

    /// The most recently loaded workout, kept so a failed schedule can return to
    /// the preview (to change the date) instead of dead-ending.
    private(set) var lastWorkout: ItwWorkout?
    /// The most recently fetched plan, so we can return to it.
    private(set) var lastPlan: TrainingPlan?

    /// Bumped per plan fetch so a slow response from a superseded load (launch
    /// vs. foreground vs. manual refresh overlapping) can't overwrite a newer one.
    private var planLoadGen = 0

    init() {
        // Restore synchronously, before the first frame: doing this in a .task
        // would flash the "No workout yet" empty state on every launch. The file
        // is a few KB, and the alternative is a visible flicker at every cold
        // start — the read is cheap enough to pay for on the main thread here.
        if let cached = PlanCache.read(), !cached.workouts.isEmpty {
            lastPlan = cached
            state = .loadedPlan(cached)
        }
    }

    /// True when the screen is showing plan-or-nothing, i.e. it is safe for an
    /// AUTOMATIC refresh to replace it. A previewed workout, a schedule result
    /// or an error is the user's own context — a background refresh must never
    /// yank them out of it (a `.itw` opened via onOpenURL races the launch load).
    private var isAutoReplaceable: Bool {
        switch state {
        case .empty, .loading, .loadedPlan: return true
        case .loaded, .scheduled, .failed: return false
        }
    }

    /// The server has no plan for us any more. Drop the local copies so a later
    /// launch can't resurrect workouts that no longer exist — the mirror of
    /// `adopt`, minus the screen state, which each caller reports differently.
    private func discardPlan() {
        writeGen += 1                     // strand an in-flight readiness write
        lastPlan = nil
        PlanCache.clear()
        SharedStore.clear()
        WidgetCenter.shared.reloadAllTimelines()
        Task { await Notifications.cancelMorningBriefs() }
    }

    /// Single path for "we have a fresh plan": state, cache, widgets, briefs.
    /// Everything that fetches a plan goes through here so the cache can't drift
    /// out of sync with what the UI is showing.
    private func adopt(_ plan: TrainingPlan, from source: PlanNetworkSource) {
        lastPlan = plan
        state = .loadedPlan(plan)
        autoLoadNotice = nil
        PlanCache.write(plan)
        // Feed the widgets: precomputed 7-day snapshot into the App Group, then
        // ask WidgetKit to rebuild timelines.
        writeWidgetSnapshot(plan, source: source)
        Task { await Notifications.rescheduleMorningBriefs(from: plan) }
    }

    func importFrom(_ source: WorkoutSource) async {
        state = .loading
        do {
            let workout = try await source.load()
            lastWorkout = workout
            state = .loaded(workout)
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    /// Fetch the whole plan from the backend and show the Today view. The
    /// USER-INITIATED path (the button / toolbar refresh): it owns the screen,
    /// so it may show a spinner and report failure as an error state.
    func loadPlan(from source: PlanNetworkSource) async {
        planLoadGen += 1
        let gen = planLoadGen
        state = .loading
        autoLoadNotice = nil
        do {
            let plan = try await source.loadPlan()
            guard gen == planLoadGen else { return }   // superseded
            if plan.workouts.isEmpty {
                // The server says there is no plan — the cache is now a lie, and
                // keeping it would restore the deleted workouts on next launch.
                // Same disposal as the automatic path; only the reporting differs.
                discardPlan()
                state = .failed("No plan yet — generate one in the web app first.")
            } else {
                adopt(plan, from: source)
            }
        } catch {
            guard gen == planLoadGen else { return }
            state = .failed(error.localizedDescription)
        }
    }

    /// The AUTOMATIC path: run at launch and on every foreground so the Today
    /// view is current without anyone pressing anything.
    ///
    /// Differs from `loadPlan` in the two ways an unrequested action must:
    /// it never replaces the user's own context (see `isAutoReplaceable`), and
    /// it never dead-ends them in a full-screen error. With a cached plan on
    /// screen a failure stays silent and keeps the plan; with nothing to show
    /// it falls back to the empty state carrying a reason, so the manual
    /// button and the file importer both remain reachable.
    func autoLoadPlan(from source: PlanNetworkSource) async {
        guard isAutoReplaceable else { return }
        planLoadGen += 1
        let gen = planLoadGen
        let hadPlan = lastPlan != nil
        // Only spin when there is nothing to look at — a cached plan refreshes
        // underneath the user without the screen flickering through .loading.
        if !hadPlan, case .empty = state { state = .loading }
        do {
            let plan = try await source.loadPlan()
            guard gen == planLoadGen, isAutoReplaceable else { return }
            if plan.workouts.isEmpty {
                // Not an error: a signed-in athlete who hasn't generated a plan.
                discardPlan()
                autoLoadNotice = "No plan yet — generate one in the web app, then refresh."
                state = .empty
            } else {
                adopt(plan, from: source)
            }
        } catch {
            guard gen == planLoadGen, isAutoReplaceable else { return }
            if hadPlan { return }   // keep showing the cached plan, stay quiet
            autoLoadNotice = "Couldn't reach Iron Trainer — \(error.localizedDescription)"
            state = .empty
        }
    }

    /// Re-fetch the plan WITHOUT flipping through .loading — used after a
    /// check-in so the Today view (and any presented sheet) stays mounted.
    /// Failures keep the current plan; the check-in already reported status.
    func refreshPlanQuietly(from source: PlanNetworkSource) async {
        planLoadGen += 1
        let gen = planLoadGen
        // A FAILED fetch and an EMPTY plan are different answers and must not
        // share a branch: the first means "keep what we have", the second means
        // the plan is gone and the local copies are now stale.
        guard let plan = try? await source.loadPlan() else { return }
        guard gen == planLoadGen else { return }
        if plan.workouts.isEmpty {
            discardPlan()
            state = .empty
        } else {
            adopt(plan, from: source)
        }
    }

    /// Bumped on every snapshot write so a slow readiness fetch from a superseded
    /// write can't clobber a newer snapshot (overlapping loadPlan + refresh).
    private var writeGen = 0

    /// Write the widget snapshot: the plan first (so a readiness-fetch failure
    /// never costs us the plan data), then re-write with today's readiness glance
    /// once fetched. The first write carries forward the last-known readiness, so a
    /// failed fetch keeps yesterday's call rather than blanking the widget. Each
    /// write reloads the timelines.
    private func writeWidgetSnapshot(_ plan: TrainingPlan, source: PlanNetworkSource) {
        writeGen += 1
        let gen = writeGen
        let carried = SharedStore.read()?.readiness
        SharedStore.write(WidgetSnapshot.build(from: plan, readiness: carried))
        WidgetCenter.shared.reloadAllTimelines()
        Task {
            guard let readiness = await source.readinessSnapshot() else { return }
            // A newer write superseded us — don't clobber its snapshot.
            guard writeGen == gen else { return }
            SharedStore.write(WidgetSnapshot.build(from: plan, readiness: readiness))
            WidgetCenter.shared.reloadAllTimelines()
        }
    }

    /// Return to the plan list (e.g. after scheduling one workout).
    func backToPlan() {
        if let p = lastPlan { state = .loadedPlan(p) }
    }

    /// Schedule every workout whose planned date is within the ±7-day window, up to
    /// WorkoutKit's 15-scheduled cap. Reports how many were scheduled / skipped.
    func scheduleAllWithinWindow(_ workouts: [ItwWorkout]) async {
        let inWindow = workouts.filter {
            guard let d = $0.plannedDate else { return false }
            return WorkoutScheduling.window.contains(Calendar.current.startOfDay(for: d))
        }
        let batch = Array(inWindow.prefix(15))
        var ok = 0
        for w in batch {
            do {
                try await WorkoutScheduling.schedule(w, on: WorkoutScheduling.defaultDate(for: w))
                ok += 1
            } catch { /* skip individual failures, keep going */ }
        }
        if ok == 0 {
            state = .failed("No workouts fall within the next 7 days to schedule.")
        } else {
            let capped = inWindow.count > 15 ? " (15 max at a time)" : ""
            state = .scheduled("Scheduled \(ok) workout\(ok == 1 ? "" : "s") to your Apple Watch\(capped). Open the Workout app to start them.")
        }
    }

    func schedule(_ itw: ItwWorkout, on date: Date) async {
        lastWorkout = itw  // failure path returns to the preview to change date
        do {
            try await WorkoutScheduling.schedule(itw, on: date)
            let f = DateFormatter(); f.dateStyle = .medium
            state = .scheduled("Scheduled \(itw.title ?? "workout") for \(f.string(from: date)). Open the Workout app on your Apple Watch to start it.")
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    /// Return to the loaded workout (e.g. after a failure) so the user can adjust
    /// the date and try again.
    func editWorkout() {
        if let w = lastWorkout { state = .loaded(w) }
    }

    func reset() {
        state = .empty
        autoLoadNotice = nil
    }

    /// Drop every trace of the current athlete's plan. Must undo everything
    /// `adopt` writes, not just the disk cache — the widget snapshot lives in the
    /// App Group and is readable with no authentication, and morning briefs are
    /// already queued with the OS. Called on sign-out AND on re-pair (a new
    /// pairing is a new athlete even when the server URL is unchanged).
    ///
    /// Both generation counters are bumped so a plan fetch or a readiness write
    /// still in flight against the OLD session can't repopulate anything after
    /// this returns.
    func forgetPlan() {
        planLoadGen += 1   // strand a plan fetch in flight against the old session
        discardPlan()
        reset()
    }
}
