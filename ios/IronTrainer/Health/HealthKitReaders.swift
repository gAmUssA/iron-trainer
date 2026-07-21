import Foundation
import HealthKit

// Reader layer for native HealthKit ingestion (epic iron-trainer-2f2c, task
// iron-trainer-aw8t). One anchored delta-read per type, unit-normalized, with
// deletions surfaced. Aggregation (HRV sleep-window mean, RHR yesterday
// recompute, night assembly) belongs to the NightAssembler (iron-trainer-90iu);
// the POST + background wiring belong to the IngestClient (iron-trainer-n1zt).

/// Persists one `HKQueryAnchor` per HealthKit type across launches.
///
/// The anchor is advanced ONLY after the server confirms ingest (at-least-once
/// delivery; the backend upsert dedupes), so the reader returns a *candidate*
/// anchor and the caller commits it here after a successful POST. On first read
/// the anchor is nil and HealthKit returns the full history.
struct AnchorStore {
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    private func key(_ id: String) -> String { "healthkit.anchor.\(id)" }

    func anchor(for id: String) -> HKQueryAnchor? {
        guard let data = defaults.data(forKey: key(id)) else { return nil }
        return try? NSKeyedUnarchiver.unarchivedObject(ofClass: HKQueryAnchor.self, from: data)
    }

    func save(_ anchor: HKQueryAnchor, for id: String) {
        guard let data = try? NSKeyedArchiver.archivedData(
            withRootObject: anchor, requiringSecureCoding: true
        ) else { return }
        defaults.set(data, forKey: key(id))
    }
}

/// A HealthKit quantity metric we ingest, with the canonical unit we read it in.
/// (`rawValue` doubles as the anchor key.)
enum QuantityMetric: String, CaseIterable {
    case hrv
    case restingHeartRate
    case respiratoryRate
    case wristTemperature
    case bodyMass
    case vo2Max

    var identifier: HKQuantityTypeIdentifier {
        switch self {
        case .hrv: .heartRateVariabilitySDNN
        case .restingHeartRate: .restingHeartRate
        case .respiratoryRate: .respiratoryRate
        case .wristTemperature: .appleSleepingWristTemperature
        case .bodyMass: .bodyMass
        case .vo2Max: .vo2Max
        }
    }

    var type: HKQuantityType { HKQuantityType(identifier) }

    /// The unit each sample's value is extracted in — the canonical unit the
    /// backend expects (ms, bpm, br/min, °C, kg, mL/kg·min).
    var unit: HKUnit {
        switch self {
        case .hrv: .secondUnit(with: .milli)                       // ms (SDNN)
        case .restingHeartRate, .respiratoryRate:
            .count().unitDivided(by: .minute())                    // per minute
        case .wristTemperature: .degreeCelsius()
        case .bodyMass: .gramUnit(with: .kilo)                     // kg
        case .vo2Max: HKUnit(from: "ml/kg*min")                    // mL/(kg·min)
        }
    }
}

/// One unit-normalized quantity sample. `sourceBundleID` lets the assembler pick
/// a single winning source per night (samples are never merged across sources).
/// `uuid` is the HealthKit sample id — the SAME key `deletedUUIDs` uses, so the
/// downstream store can locate and remove a sample that's later edited/deleted.
struct QuantitySample: Equatable {
    let metric: QuantityMetric
    let value: Double
    let start: Date
    let end: Date
    let sourceBundleID: String
    let uuid: UUID
}

/// One sleep-stage interval (category sample), stage preserved raw for the
/// assembler to sessionize + union. `uuid` mirrors `deletedUUIDs` (see above).
struct SleepSample: Equatable {
    let stage: HKCategoryValueSleepAnalysis
    let start: Date
    let end: Date
    let sourceBundleID: String
    let uuid: UUID
}

/// The result of a delta read: what changed since the caller's anchor, plus the
/// candidate `newAnchor` to commit after ingest confirms.
struct MetricDelta<Sample> {
    let samples: [Sample]
    let deletedUUIDs: [UUID]
    let newAnchor: HKQueryAnchor
}

/// Delta-reads recovery metrics from HealthKit via anchored queries. Pure data —
/// no UI, no networking — so it's callable from a background observer handler.
final class HealthKitReader {
    private let store: HKHealthStore
    private let anchors: AnchorStore
    private let sleepAnchorID = HKCategoryTypeIdentifier.sleepAnalysis.rawValue

    /// How far back the FIRST read (nil anchor) reaches. Bounds the initial
    /// result so a long-time Health user isn't loaded years of samples in one
    /// unbounded array; readiness (chronic ≤42 d) and ~6 months of native
    /// trend fit comfortably. Older history stays in the backend from HAE.
    private let historyWindowDays = 180

    init(store: HKHealthStore = HKHealthStore(), anchors: AnchorStore = AnchorStore()) {
        self.store = store
        self.anchors = anchors
    }

    /// Read new + deleted samples for one quantity metric since its persisted
    /// anchor. Does NOT persist the returned anchor — call `commit` after ingest.
    func readQuantity(_ metric: QuantityMetric) async throws -> MetricDelta<QuantitySample> {
        let unit = metric.unit
        return try await delta(
            predicate: .quantitySample(type: metric.type, predicate: windowPredicate),
            anchorID: metric.rawValue
        ) {
            QuantitySample(
                metric: metric,
                value: $0.quantity.doubleValue(for: unit),
                start: $0.startDate,
                end: $0.endDate,
                sourceBundleID: $0.sourceRevision.source.bundleIdentifier,
                uuid: $0.uuid
            )
        }
    }

    /// Read new + deleted sleep-stage samples since the persisted anchor. All
    /// stages (inBed/awake/asleep*) are returned raw; the assembler decides what
    /// counts toward total sleep.
    func readSleep() async throws -> MetricDelta<SleepSample> {
        try await delta(
            predicate: .categorySample(type: HKCategoryType(.sleepAnalysis), predicate: windowPredicate),
            anchorID: sleepAnchorID
        ) { sample in
            guard let stage = HKCategoryValueSleepAnalysis(rawValue: sample.value) else { return nil }
            return SleepSample(
                stage: stage,
                start: sample.startDate,
                end: sample.endDate,
                sourceBundleID: sample.sourceRevision.source.bundleIdentifier,
                uuid: sample.uuid
            )
        }
    }

    /// Bounds every read to the last `historyWindowDays`, capping the first
    /// (nil-anchor) batch. Recomputed each call so the window tracks forward.
    private var windowPredicate: NSPredicate {
        let cutoff = Calendar.current.date(byAdding: .day, value: -historyWindowDays, to: Date())
        return HKQuery.predicateForSamples(withStart: cutoff, end: nil)
    }

    /// Shared anchored delta-read: build the descriptor from the persisted
    /// anchor, map added samples via `transform` (nil drops the sample), and
    /// surface deletions + the candidate anchor. Only the predicate + transform
    /// differ between the quantity and sleep readers.
    private func delta<S: HKSample, Out>(
        predicate: HKSamplePredicate<S>,
        anchorID: String,
        transform: @escaping (S) -> Out?
    ) async throws -> MetricDelta<Out> {
        let descriptor = HKAnchoredObjectQueryDescriptor(
            predicates: [predicate],
            anchor: anchors.anchor(for: anchorID)
        )
        let result = try await descriptor.result(for: store)
        return MetricDelta(
            samples: result.addedSamples.compactMap(transform),
            deletedUUIDs: result.deletedObjects.map(\.uuid),
            newAnchor: result.newAnchor
        )
    }

    /// Persist an anchor — call ONLY after the server has confirmed ingest, so a
    /// failed POST re-reads the same delta next time (at-least-once).
    func commit(anchor: HKQueryAnchor, for metric: QuantityMetric) {
        anchors.save(anchor, for: metric.rawValue)
    }

    func commitSleep(anchor: HKQueryAnchor) {
        anchors.save(anchor, for: sleepAnchorID)
    }
}
