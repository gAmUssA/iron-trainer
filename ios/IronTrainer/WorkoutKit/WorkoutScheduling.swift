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

    /// Selectable scheduling window: today → 7 days out. WorkoutScheduler accepts
    /// ±7 days, but scheduling a workout in the past isn't useful, so we start today.
    static var window: ClosedRange<Date> {
        let cal = Calendar.current
        let start = cal.startOfDay(for: .now)
        let end = cal.date(byAdding: .day, value: 7, to: start) ?? start
        return start...end
    }

    /// The date to pre-select: the workout's planned date if it's inside the
    /// window, otherwise clamped to the nearest valid day (so a months-out plan
    /// lands on a schedulable day instead of failing).
    static func defaultDate(for itw: ItwWorkout) -> Date {
        let w = window
        guard let planned = itw.plannedDate else { return w.lowerBound }
        return min(max(planned, w.lowerBound), w.upperBound)
    }

    /// Whether the workout's own planned date is already schedulable.
    static func plannedDateIsSchedulable(_ itw: ItwWorkout) -> Bool {
        guard let planned = itw.plannedDate else { return false }
        return window.contains(Calendar.current.startOfDay(for: planned))
    }

    /// Schedule the plan on `date`. Throws if scheduling is unsupported, not
    /// authorized, or `date` is outside the ±7-day window (the API ignores such
    /// dates silently, so we pre-check and surface it instead).
    static func schedule(_ itw: ItwWorkout, on date: Date) async throws {
        guard WorkoutScheduler.isSupported else { throw SchedulingError.unsupported }
        let state = await ensureAuthorized()
        guard state == .authorized else { throw SchedulingError.notAuthorized }
        guard window.contains(Calendar.current.startOfDay(for: date)) else {
            throw SchedulingError.outsideWindow
        }
        let cal = Calendar.current
        var comps = cal.dateComponents([.year, .month, .day], from: date)
        if cal.isDateInToday(date) {
            // Today's 6am may already be past; schedule ~1h out so it's upcoming.
            // After 23:00, now+1h rolls into tomorrow while comps' day stays
            // today — WorkoutScheduler silently ignores past times, so clamp to
            // the last slot of today instead.
            let soonDate = Date.now.addingTimeInterval(3600)
            if cal.isDate(soonDate, inSameDayAs: date) {
                let soon = cal.dateComponents([.hour, .minute], from: soonDate)
                comps.hour = soon.hour; comps.minute = soon.minute
            } else {
                comps.hour = 23; comps.minute = 45
            }
        } else {
            comps.hour = 6; comps.minute = 0
        }
        let plan = try ItwToWorkoutKit.makePlan(from: itw)
        // schedule(_:at:) is async-only and does NOT throw.
        await WorkoutScheduler.shared.schedule(plan, at: comps)
    }

    enum SchedulingError: LocalizedError {
        case unsupported
        case notAuthorized
        case outsideWindow

        var errorDescription: String? {
            switch self {
            case .unsupported: return "Scheduling workouts to Apple Watch isn't supported on this device."
            case .notAuthorized: return "Allow Iron Trainer to schedule workouts in Settings to continue."
            case .outsideWindow: return "Apple Watch only accepts workouts within the next 7 days. Pick a closer date."
            }
        }
    }
}
