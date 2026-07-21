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

    /// The most recently loaded workout, kept so a failed schedule can return to
    /// the preview (to change the date) instead of dead-ending.
    private(set) var lastWorkout: ItwWorkout?
    /// The most recently fetched plan, so we can return to it.
    private(set) var lastPlan: TrainingPlan?

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

    /// Fetch the whole plan from the backend and show the Today view.
    func loadPlan(from source: PlanNetworkSource) async {
        state = .loading
        do {
            let plan = try await source.loadPlan()
            lastPlan = plan
            if plan.workouts.isEmpty {
                state = .failed("No plan yet — generate one in the web app first.")
            } else {
                state = .loadedPlan(plan)
                // Feed the widgets: precomputed 7-day snapshot into the App
                // Group, then ask WidgetKit to rebuild timelines.
                writeWidgetSnapshot(plan, source: source)
                Task { await Notifications.rescheduleMorningBriefs(from: plan) }
            }
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    /// Re-fetch the plan WITHOUT flipping through .loading — used after a
    /// check-in so the Today view (and any presented sheet) stays mounted.
    /// Failures keep the current plan; the check-in already reported status.
    func refreshPlanQuietly(from source: PlanNetworkSource) async {
        guard let plan = try? await source.loadPlan(), !plan.workouts.isEmpty else { return }
        lastPlan = plan
        state = .loadedPlan(plan)
        writeWidgetSnapshot(plan, source: source)
        Task { await Notifications.rescheduleMorningBriefs(from: plan) }
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

    func reset() { state = .empty }
}
