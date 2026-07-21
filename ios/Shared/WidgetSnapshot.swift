import Foundation

/// Compact, render-ready view of the plan the app drops into the App Group for
/// the widgets: race meta + the next 7 days, with intensity profiles already
/// computed. The widget never sees ItwWorkout or thresholds.
struct WidgetSnapshot: Codable, Equatable {
    let generatedAt: Date
    let raceName: String?
    let raceDate: String?  // ISO YYYY-MM-DD
    let days: [Day]        // today + next 6, chronological
    /// Today's readiness/recovery glance (nil until the app fetches it). Optional
    /// so older snapshots without the field still decode.
    var readiness: Readiness?

    struct Day: Codable, Equatable {
        let date: String   // YYYY-MM-DD
        let workouts: [WorkoutSummary]
    }

    /// Render-ready readiness snapshot the app fetches (readiness/today + latest
    /// pmc row + latest recovery row) and the widget only reads.
    struct Readiness: Codable, Equatable {
        let call: String?    // hard / easy / rest
        let level: String?   // green / amber / red
        let hrvMs: Double?
        let rhrBpm: Double?
        let ctl: Double?
        let atl: Double?
        let tsb: Double?
        let reason: String?  // reasons[0]
        /// The day this call is for (YYYY-MM-DD) — the widget marks it stale on
        /// any later entry (each upcoming-midnight entry shares one snapshot).
        var day: String?

        /// True for any entry whose day is past the day this call was computed for.
        func isStale(on entryDay: String) -> Bool {
            guard let day else { return false }  // old snapshot w/o the field: treat as current
            return entryDay > day
        }
    }

    struct WorkoutSummary: Codable, Equatable {
        let sport: String?
        let title: String?
        let durationS: Int?
        let fuel: String?
        let profile: [ProfileSegment]
    }
}

extension WidgetSnapshot {
    /// Build the render-ready snapshot from a fetched plan: today + the next 6
    /// days, with fueling lines parsed and intensity profiles precomputed.
    static func build(from plan: TrainingPlan, today: Date = .now,
                      readiness: Readiness? = nil) -> WidgetSnapshot {
        let cal = Calendar.current
        let start = cal.startOfDay(for: today)
        let iso = { (d: Date) -> String in
            let c = cal.dateComponents([.year, .month, .day], from: d)
            return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
        }
        let byDate = Dictionary(grouping: plan.workouts) { $0.date ?? "" }
        let days = (0..<7).map { offset -> Day in
            let date = cal.date(byAdding: .day, value: offset, to: start) ?? start
            let key = iso(date)
            let workouts = (byDate[key] ?? []).map { w in
                WorkoutSummary(
                    sport: w.sport,
                    title: w.title,
                    durationS: w.durationS,
                    fuel: FuelParser.fuelLine(from: w.description),
                    profile: WorkoutIntensity.segments(for: w, athlete: w.athlete)
                )
            }
            return Day(date: key, workouts: workouts)
        }
        return WidgetSnapshot(
            generatedAt: today,
            raceName: plan.meta?.raceName,
            raceDate: plan.meta?.raceDate,
            days: days,
            readiness: readiness
        )
    }
}

extension WidgetSnapshot {
    /// Sample used by widget placeholders (system-redacted) and SwiftUI previews.
    static var sample: WidgetSnapshot {
        let today = Calendar.current.startOfDay(for: .now)
        let fmt = { (d: Date) -> String in
            let c = Calendar.current.dateComponents([.year, .month, .day], from: d)
            return String(format: "%04d-%02d-%02d", c.year!, c.month!, c.day!)
        }
        let bikeProfile: [ProfileSegment] = [
            .init(weight: 600, intensity: 0.55, kind: "warmup"),
            .init(weight: 300, intensity: 1.05, kind: "interval"),
            .init(weight: 180, intensity: 0.5, kind: "recovery"),
            .init(weight: 300, intensity: 1.05, kind: "interval"),
            .init(weight: 180, intensity: 0.5, kind: "recovery"),
            .init(weight: 300, intensity: 1.1, kind: "interval"),
            .init(weight: 600, intensity: 0.45, kind: "cooldown"),
        ]
        let runProfile: [ProfileSegment] = [
            .init(weight: 600, intensity: 0.6, kind: "warmup"),
            .init(weight: 2400, intensity: 0.85, kind: "steady"),
            .init(weight: 300, intensity: 0.5, kind: "cooldown"),
        ]
        return WidgetSnapshot(
            generatedAt: .now,
            raceName: "IRONMAN 70.3 New York",
            raceDate: fmt(Calendar.current.date(byAdding: .day, value: 80, to: today)!),
            days: (0..<7).map { offset in
                let d = Calendar.current.date(byAdding: .day, value: offset, to: today)!
                let workouts: [WorkoutSummary]
                switch offset {
                case 0: workouts = [.init(sport: "Bike", title: "VO2 intervals 5×5",
                                          durationS: 3600, fuel: "60 g carbs/h · 750 mL fluid/h",
                                          profile: bikeProfile)]
                case 1: workouts = [.init(sport: "Run", title: "Tempo 40min",
                                          durationS: 3300, fuel: nil, profile: runProfile)]
                case 3: workouts = []
                default: workouts = [.init(sport: "Swim", title: "Endurance 2500m",
                                           durationS: 3000, fuel: nil, profile: runProfile)]
                }
                return Day(date: fmt(d), workouts: workouts)
            },
            readiness: Readiness(
                call: "easy", level: "amber",
                hrvMs: 49, rhrBpm: 58,
                ctl: 62, atl: 71, tsb: -9,
                reason: "HRV below 7-day baseline",
                day: fmt(today)
            )
        )
    }
}
