import Foundation

/// Drives the import → preview → schedule flow.
@MainActor
final class ImportModel: ObservableObject {
    enum State: Equatable {
        case empty
        case loading
        case loaded(ItwWorkout)
        case loadedPlan([ItwWorkout])   // a fetched plan, pick one to schedule
        case scheduled(String)          // human summary
        case failed(String)
    }

    @Published private(set) var state: State = .empty

    /// The most recently loaded workout, kept so a failed schedule can return to
    /// the preview (to change the date) instead of dead-ending.
    private(set) var lastWorkout: ItwWorkout?
    /// The most recently fetched plan, so we can return to the list.
    private(set) var lastPlan: [ItwWorkout]?

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

    /// Fetch the whole plan from the backend and show it as a list.
    func loadPlan(from source: PlanNetworkSource) async {
        state = .loading
        do {
            let workouts = try await source.loadPlan()
            lastPlan = workouts
            state = workouts.isEmpty
                ? .failed("No plan yet — generate one in the web app first.")
                : .loadedPlan(workouts)
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    /// Open a workout from the plan list in the date-picker preview.
    func select(_ workout: ItwWorkout) {
        lastWorkout = workout
        state = .loaded(workout)
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
