import Foundation
import WorkoutKit

/// Thin wrapper over WorkoutScheduler — authorization + schedule-to-date.
enum WorkoutScheduling {

    /// Ask the user to allow Iron Trainer to schedule workouts. Idempotent.
    @discardableResult
    static func ensureAuthorized() async -> WorkoutScheduler.AuthorizationState {
        let state = await WorkoutScheduler.shared.authorizationState
        if state == .authorized { return state }
        return await WorkoutScheduler.shared.requestAuthorization()
    }

    /// Schedule the plan on the .itw's planned date. Throws if not authorized or
    /// the date is missing/invalid.
    static func schedule(_ itw: ItwWorkout) async throws {
        let state = await ensureAuthorized()
        guard state == .authorized else { throw SchedulingError.notAuthorized }
        guard let components = itw.scheduleComponents else { throw SchedulingError.noDate }
        let plan = try ItwToWorkoutKit.makePlan(from: itw)
        try await WorkoutScheduler.shared.schedule(plan, at: components)
    }

    enum SchedulingError: LocalizedError {
        case notAuthorized
        case noDate

        var errorDescription: String? {
            switch self {
            case .notAuthorized: return "Allow Iron Trainer to schedule workouts in Settings to continue."
            case .noDate: return "This workout has no planned date to schedule."
            }
        }
    }
}
