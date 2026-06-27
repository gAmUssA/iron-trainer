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

    /// Schedule the plan on the .itw's planned date. Throws if scheduling is
    /// unsupported, not authorized, the date is missing, or it falls outside the
    /// WorkoutScheduler's ±7-day window (the API silently ignores such dates).
    static func schedule(_ itw: ItwWorkout) async throws {
        guard WorkoutScheduler.isSupported else { throw SchedulingError.unsupported }
        let state = await ensureAuthorized()
        guard state == .authorized else { throw SchedulingError.notAuthorized }
        guard let components = itw.scheduleComponents else { throw SchedulingError.noDate }
        guard let when = Calendar.current.date(from: components),
              isWithinSchedulingWindow(when) else { throw SchedulingError.outsideWindow }
        let plan = try ItwToWorkoutKit.makePlan(from: itw)
        // schedule(_:at:) is async-only and does NOT throw; rejection of an
        // out-of-window date is silent, which is why we pre-check above.
        await WorkoutScheduler.shared.schedule(plan, at: components)
    }

    /// WorkoutScheduler only accepts dates within ±7 days of now.
    private static func isWithinSchedulingWindow(_ date: Date) -> Bool {
        let now = Date.now
        return date >= now.addingTimeInterval(-7 * 86_400)
            && date <= now.addingTimeInterval(7 * 86_400)
    }

    enum SchedulingError: LocalizedError {
        case unsupported
        case notAuthorized
        case noDate
        case outsideWindow

        var errorDescription: String? {
            switch self {
            case .unsupported: return "Scheduling workouts to Apple Watch isn't supported on this device."
            case .notAuthorized: return "Allow Iron Trainer to schedule workouts in Settings to continue."
            case .noDate: return "This workout has no planned date to schedule."
            case .outsideWindow: return "Apple Watch only accepts workouts within 7 days. Schedule this closer to its date."
            }
        }
    }
}
