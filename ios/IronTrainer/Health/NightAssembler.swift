import Foundation
import HealthKit

// Turns raw HealthKit reader output (SleepSample[], QuantitySample[]) into per-day
// recovery records — the part Health Auto Export did server-side for us (epic
// iron-trainer-2f2c, task iron-trainer-90iu). Pure functions over samples, no
// HealthKit runtime, so it's fully unit-tested. The IngestClient (n1zt) maps
// these records to the backend's HAE-shaped payload and POSTs them.

/// One day's assembled recovery values. Sleep is per-stage hours; gauges are
/// daily rollups. `day` is the local start-of-day for the wake-up date.
struct DailyRecovery: Equatable {
    let day: Date
    var deepH: Double?
    var coreH: Double?
    var remH: Double?
    var unspecifiedH: Double?
    var awakeH: Double?
    var hrvMs: Double?
    var rhrBpm: Double?
    var respiratoryRate: Double?
    var wristTempC: Double?
    var bodyMassKg: Double?
    var vo2Max: Double?

    /// Total asleep = deep+core+rem+unspecified. `inBed` and `awake` are excluded
    /// (per research), so the bar never overstates sleep.
    var sleepH: Double? {
        let parts = [deepH, coreH, remH, unspecifiedH].compactMap { $0 }
        return parts.isEmpty ? nil : parts.reduce(0, +)
    }
}

enum NightAssembler {
    private static let secondsPerHour = 3600.0

    /// The wake-up-date night a sample belongs to. The night window is
    /// prev-day 15:00 → today 15:00, so shifting the start by +9h and taking the
    /// local day lands every in-window sample on its wake-up date:
    /// D-1 15:00 → D 00:00, D 14:59 → D 23:59, D 15:00 → D+1 00:00.
    static func nightDate(for start: Date, _ cal: Calendar) -> Date {
        cal.startOfDay(for: start.addingTimeInterval(9 * secondsPerHour))
    }

    /// Total covered seconds of a set of half-open [start,end) intervals, unioning
    /// overlaps so a source that re-syncs the same night (Garmin) isn't
    /// double-counted.
    static func unionSeconds(_ intervals: [(start: Date, end: Date)]) -> Double {
        let sorted = intervals.filter { $0.end > $0.start }.sorted { $0.start < $1.start }
        guard var cur = sorted.first else { return 0 }
        var total = 0.0
        for iv in sorted.dropFirst() {
            if iv.start > cur.end {                 // disjoint — bank the run
                total += cur.end.timeIntervalSince(cur.start)
                cur = iv
            } else if iv.end > cur.end {            // overlap — extend
                cur.end = iv.end
            }
        }
        return total + cur.end.timeIntervalSince(cur.start)
    }

    private static let asleepStages: Set<HKCategoryValueSleepAnalysis> =
        [.asleepCore, .asleepDeep, .asleepREM, .asleepUnspecified]

    /// Assemble nightly sleep + daily gauges into one record per day.
    static func assemble(
        sleep: [SleepSample],
        quantities: [QuantitySample],
        calendar: Calendar = .current
    ) -> [DailyRecovery] {
        var byDay: [Date: DailyRecovery] = [:]
        func record(_ day: Date) -> DailyRecovery { byDay[day] ?? DailyRecovery(day: day) }

        // ── Sleep: one winning source per night, union per stage ──────────────
        let sleepByNight = Dictionary(grouping: sleep) { nightDate(for: $0.start, calendar) }
        var windowByNight: [Date: (start: Date, end: Date)] = [:]

        for (night, samples) in sleepByNight {
            // Winning source = the one with the most unioned asleep time.
            let bySource = Dictionary(grouping: samples, by: \.sourceBundleID)
            guard let winner = bySource.max(by: { a, b in
                asleepSeconds(a.value) < asleepSeconds(b.value)
            })?.value, asleepSeconds(winner) > 0 else { continue }

            var rec = record(night)
            rec.deepH = hours(winner, stage: .asleepDeep)
            rec.coreH = hours(winner, stage: .asleepCore)
            rec.remH = hours(winner, stage: .asleepREM)
            rec.unspecifiedH = hours(winner, stage: .asleepUnspecified)
            rec.awakeH = hours(winner, stage: .awake)
            byDay[night] = rec

            // Sleep window = asleep span, for the overnight gauge means (HRV/resp).
            let asleep = winner.filter { asleepStages.contains($0.stage) }
            if let lo = asleep.map(\.start).min(), let hi = asleep.map(\.end).max() {
                windowByNight[night] = (lo, hi)
            }
        }

        // ── Overnight gauges: mean within the night's sleep window ────────────
        for (night, window) in windowByNight {
            var rec = record(night)
            rec.hrvMs = mean(quantities, .hrv, within: window)
            rec.respiratoryRate = mean(quantities, .respiratoryRate, within: window)
            rec.wristTempC = mean(quantities, .wristTemperature, within: window)
            byDay[night] = rec
        }

        // ── Calendar-day gauges ───────────────────────────────────────────────
        applyDaily(quantities, .restingHeartRate, calendar, reduce: average,
                   set: { rec, v in rec.rhrBpm = v }, into: &byDay)
        applyDaily(quantities, .bodyMass, calendar, reduce: latest,
                   set: { rec, v in rec.bodyMassKg = v }, into: &byDay)
        applyDaily(quantities, .vo2Max, calendar, reduce: latest,
                   set: { rec, v in rec.vo2Max = v }, into: &byDay)

        return byDay.values.sorted { $0.day < $1.day }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static func asleepSeconds(_ samples: [SleepSample]) -> Double {
        unionSeconds(samples.filter { asleepStages.contains($0.stage) }.map { ($0.start, $0.end) })
    }

    private static func hours(_ samples: [SleepSample], stage: HKCategoryValueSleepAnalysis) -> Double? {
        let intervals = samples.filter { $0.stage == stage }.map { ($0.start, $0.end) }
        guard !intervals.isEmpty else { return nil }
        return unionSeconds(intervals) / secondsPerHour
    }

    private static func mean(
        _ samples: [QuantitySample], _ metric: QuantityMetric,
        within window: (start: Date, end: Date)
    ) -> Double? {
        let vals = samples
            .filter { $0.metric == metric && $0.start >= window.start && $0.start <= window.end }
            .map(\.value)
        return vals.isEmpty ? nil : vals.reduce(0, +) / Double(vals.count)
    }

    private static func average(_ v: [Double]) -> Double { v.reduce(0, +) / Double(v.count) }
    private static func latest(_ v: [Double]) -> Double { v.last ?? 0 }

    /// Group one metric's samples by calendar day, reduce, and apply to the day's
    /// record. `reduce` receives the day's values in chronological order.
    private static func applyDaily(
        _ samples: [QuantitySample], _ metric: QuantityMetric, _ calendar: Calendar,
        reduce: ([Double]) -> Double,
        set: (inout DailyRecovery, Double) -> Void,
        into byDay: inout [Date: DailyRecovery]
    ) {
        let ofMetric = samples.filter { $0.metric == metric }.sorted { $0.start < $1.start }
        let byCalDay = Dictionary(grouping: ofMetric) { calendar.startOfDay(for: $0.start) }
        for (day, daySamples) in byCalDay {
            var rec = byDay[day] ?? DailyRecovery(day: day)
            set(&rec, reduce(daySamples.map(\.value)))
            byDay[day] = rec
        }
    }
}
