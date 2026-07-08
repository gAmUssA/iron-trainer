import Foundation

/// One renderable bar of a workout profile: relative width (time share), height
/// (intensity vs threshold, nil = open/unknown), and the step kind for styling.
/// Precomputed in the app and stored in the widget snapshot so the widget
/// extension never touches workout models or threshold math.
struct ProfileSegment: Codable, Equatable {
    let weight: Double        // relative width (seconds, pre-normalization)
    let intensity: Double?    // ~0.2–1.2 of threshold; nil = open step
    let kind: String?         // warmup | cooldown | recovery | interval | steady
}

/// Pure math turning .itw steps + athlete thresholds into profile segments.
enum WorkoutIntensity {
    static let displayMin = 0.2
    static let displayMax = 1.2
    static let openHeight = 0.35  // rendered height fraction for target-less steps

    /// Fraction of threshold for one step's target. Pace is inverted (fewer
    /// seconds = faster), so faster-than-threshold correctly exceeds 1.0.
    static func intensity(target: ItwWorkout.Target?, athlete: ItwWorkout.Athlete?) -> Double? {
        guard let target, let type = target.type, type != "open" else { return nil }
        let mid = mid(of: target)
        guard mid > 0 else { return nil }
        switch type {
        case "power":
            guard let ftp = athlete?.ftp, ftp > 0 else { return nil }
            return clamp(mid / ftp)
        case "hr":
            guard let thr = athlete?.thresholdHr, thr > 0 else { return nil }
            return clamp(mid / Double(thr))
        case "pace":
            let threshold: Double? = target.unit == "sec_per_100m"
                ? athlete?.cssSwim : athlete?.thresholdPaceRun
            guard let t = threshold, t > 0 else { return nil }
            return clamp(t / mid)
        default:
            return nil
        }
    }

    /// Bar weight in seconds: duration, else estimated from distance at the
    /// target (or threshold-ish) pace, else a nominal 5 minutes.
    static func weight(step: ItwWorkout.Step, athlete: ItwWorkout.Athlete?) -> Double {
        if let s = step.durationS, s > 0 { return Double(s) }
        if let m = step.distanceM, m > 0 {
            let unitMeters: Double = step.target?.unit == "sec_per_100m" ? 100 : 1000
            let secPerUnit = step.target.map(mid(of:)).flatMap { $0 > 0 ? $0 : nil }
                ?? (step.target?.unit == "sec_per_100m" ? athlete?.cssSwim : athlete?.thresholdPaceRun)
                ?? 360
            return m / unitMeters * secPerUnit
        }
        return 300
    }

    static func segments(for workout: ItwWorkout, athlete: ItwWorkout.Athlete?) -> [ProfileSegment] {
        workout.steps.map { step in
            ProfileSegment(
                weight: weight(step: step, athlete: athlete),
                intensity: intensity(target: step.target, athlete: athlete),
                kind: step.type
            )
        }
    }

    private static func mid(of target: ItwWorkout.Target) -> Double {
        switch (target.low, target.high) {
        case let (lo?, hi?): return (lo + hi) / 2
        case let (lo?, nil): return lo
        case let (nil, hi?): return hi
        default: return 0
        }
    }

    private static func clamp(_ v: Double) -> Double {
        min(max(v, displayMin), displayMax)
    }
}
