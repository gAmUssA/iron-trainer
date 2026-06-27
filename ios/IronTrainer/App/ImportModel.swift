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

    func importFrom(_ source: WorkoutSource) async {
        state = .loading
        do {
            let workout = try await source.load()
            state = .loaded(workout)
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    func schedule(_ itw: ItwWorkout) async {
        do {
            try await WorkoutScheduling.schedule(itw)
            let when = itw.date ?? "the planned date"
            state = .scheduled("Scheduled \(itw.title ?? "workout") for \(when). Open the Workout app on your Apple Watch to start it.")
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    func reset() { state = .empty }
}
