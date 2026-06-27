import Foundation

/// Drives the import → preview → schedule flow.
@MainActor
final class ImportModel: ObservableObject {
    enum State: Equatable {
        case empty
        case loading
        case loaded(ItwWorkout)
        case scheduled(String)   // human summary
        case failed(String)
    }

    @Published private(set) var state: State = .empty

    /// The most recently loaded workout, kept so a failed schedule can return to
    /// the preview (to change the date) instead of dead-ending.
    private(set) var lastWorkout: ItwWorkout?

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
