import Foundation
import HealthKit
import WorkoutKit

/// Maps a decoded `ItwWorkout` into a native WorkoutKit `CustomWorkout` /
/// `WorkoutPlan`. WorkoutKit's binary `.workout` is produced from this on-device.
///
/// NOTE: WorkoutKit API signatures evolve across SDK seeds — verify these
/// initializers against the Xcode 16 / iOS 18 SDK before first build (see README).
enum ItwToWorkoutKit {

    static func activityType(for sport: String?) -> HKWorkoutActivityType {
        switch sport {
        case "Swim": return .swimming
        case "Bike": return .cycling
        case "Run": return .running
        case "Brick": return .cycling          // first leg; brick is bike→run
        case "Strength": return .traditionalStrengthTraining
        default: return .other
        }
    }

    /// Build a WorkoutPlan from the .itw model.
    static func makePlan(from itw: ItwWorkout) throws -> WorkoutPlan {
        let custom = try makeCustomWorkout(from: itw)
        return WorkoutPlan(.custom(custom))
    }

    static func makeCustomWorkout(from itw: ItwWorkout) throws -> CustomWorkout {
        let activity = activityType(for: itw.sport)
        let steps = itw.steps

        // First step that is a warmup, last that is a cooldown — everything in
        // between becomes a single interval block (one iteration). Richer repeat
        // structures can be added if the .itw schema ever encodes them.
        var warmup: WorkoutStep?
        var cooldown: WorkoutStep?
        var middle = steps

        if let first = middle.first, first.type == "warmup" {
            warmup = makeWorkoutStep(first, activity: activity)
            middle.removeFirst()
        }
        if let last = middle.last, last.type == "cooldown" {
            cooldown = makeWorkoutStep(last, activity: activity)
            middle.removeLast()
        }

        let intervalSteps: [IntervalStep] = middle.map { step in
            let purpose: IntervalStep.Purpose = (step.type == "recovery") ? .recovery : .work
            return IntervalStep(purpose, step: makeWorkoutStep(step, activity: activity))
        }

        let blocks: [IntervalBlock] = intervalSteps.isEmpty
            ? []
            : [IntervalBlock(steps: intervalSteps, iterations: 1)]

        return CustomWorkout(
            activity: activity,
            location: .outdoor,
            displayName: itw.title ?? "Iron Trainer workout",
            warmup: warmup,
            blocks: blocks,
            cooldown: cooldown
        )
    }

    // MARK: - Steps

    private static func makeWorkoutStep(_ step: ItwWorkout.Step,
                                        activity: HKWorkoutActivityType) -> WorkoutStep {
        WorkoutStep(goal: goal(for: step), alert: alert(for: step.target, activity: activity))
    }

    private static func goal(for step: ItwWorkout.Step) -> WorkoutGoal {
        if let seconds = step.durationS, seconds > 0 {
            return .time(Double(seconds), .seconds)
        }
        if let meters = step.distanceM, meters > 0 {
            return .distance(meters, .meters)
        }
        return .open
    }

    private static func alert(for target: ItwWorkout.Target?,
                              activity: HKWorkoutActivityType) -> (any WorkoutAlert)? {
        guard let target, let type = target.type, type != "open",
              let low = target.low, let high = target.high else { return nil }

        switch type {
        case "power":
            let lo = Measurement(value: min(low, high), unit: UnitPower.watts)
            let hi = Measurement(value: max(low, high), unit: UnitPower.watts)
            return PowerRangeAlert(target: lo...hi)

        case "hr":
            // Heart rate is a Measurement<UnitFrequency>; WorkoutKit exposes bpm
            // as WorkoutAlertMetric.countPerMinute.
            let bpm = WorkoutAlertMetric.countPerMinute
            let lo = Measurement(value: min(low, high), unit: bpm)
            let hi = Measurement(value: max(low, high), unit: bpm)
            return HeartRateRangeAlert(target: lo...hi)

        case "pace":
            // low/high are seconds (low = faster). Convert to a speed range.
            let fast = paceToSpeed(min(low, high), unit: target.unit)  // higher m/s
            let slow = paceToSpeed(max(low, high), unit: target.unit)  // lower m/s
            guard fast > 0, slow > 0 else { return nil }
            let lo = Measurement(value: slow, unit: UnitSpeed.metersPerSecond)
            let hi = Measurement(value: fast, unit: UnitSpeed.metersPerSecond)
            // Swim alerts are limited; fall back to no alert there for the MVP.
            if activity == .swimming { return nil }
            return SpeedRangeAlert(target: lo...hi, metric: .current)

        default:
            return nil
        }
    }

    /// Mirror of `fit_export._pace_to_speed`: pace seconds -> m/s.
    private static func paceToSpeed(_ paceSec: Double, unit: String?) -> Double {
        guard paceSec > 0 else { return 0 }
        switch unit {
        case "sec_per_km": return 1000.0 / paceSec
        case "sec_per_100m": return 100.0 / paceSec
        default: return 0
        }
    }
}
