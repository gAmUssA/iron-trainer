import Foundation
import HealthKit

// Native HealthKit → backend sync (epic iron-trainer-2f2c, task iron-trainer-n1zt).
// Ties the reader (aw8t) + assembler (90iu) to the existing /api/health/ingest
// endpoint, replacing Health Auto Export. Delta-read → assemble → POST → advance
// anchors only on success. The backend upserts per-field (last-write-wins), so
// sending only the fields a sync actually has is safe and incremental.

// MARK: - Payload mapping

private extension QuantityMetric {
    /// The Health-export metric name the backend maps to a daily_recovery column
    /// (mirror of HealthIngest.FIELD).
    var exportName: String {
        switch self {
        case .hrv: "heart_rate_variability"
        case .restingHeartRate: "resting_heart_rate"
        case .respiratoryRate: "respiratory_rate"
        case .wristTemperature: "apple_sleeping_wrist_temperature"
        case .bodyMass: "weight_body_mass"
        case .vo2Max: "vo2max"
        }
    }

    /// Units string sent with each metric. Chosen so the backend's converters pass
    /// the value through unchanged (toKg converts only lb; toCelsius only if it
    /// contains "f") — our values are already ms / count·min⁻¹ / °C / kg.
    var exportUnits: String {
        switch self {
        case .hrv: "ms"
        case .restingHeartRate, .respiratoryRate: "count/min"
        case .wristTemperature: "degC"
        case .bodyMass: "kg"
        case .vo2Max: "mL/min·kg"
        }
    }

    func value(from r: DailyRecovery) -> Double? {
        switch self {
        case .hrv: r.hrvMs
        case .restingHeartRate: r.rhrBpm
        case .respiratoryRate: r.respiratoryRate
        case .wristTemperature: r.wristTempC
        case .bodyMass: r.bodyMassKg
        case .vo2Max: r.vo2Max
        }
    }
}

/// Builds the HAE-shaped payload and POSTs it. Payload building is pure (static)
/// so it's unit-tested without networking.
struct HealthIngestClient {
    let baseURL: URL
    let bearer: String

    func post(_ records: [DailyRecovery]) async throws {
        var req = URLRequest(url: baseURL.appending(path: "/api/health/ingest"))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        req.httpBody = try JSONSerialization.data(withJSONObject: Self.payload(records))
        let (_, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw NetworkError.http((resp as? HTTPURLResponse)?.statusCode ?? -1)
        }
    }

    /// `{data:{metrics:[{name, units, data:[{date, qty}]}]}}`. Only non-nil fields
    /// are emitted, so a partial sync never overwrites a fuller value (per-field
    /// upsert). Sleep is one `sleep_analysis` record per night keyed by `sleepEnd`.
    static func payload(_ records: [DailyRecovery]) -> [String: Any] {
        var metrics: [[String: Any]] = []

        for metric in QuantityMetric.allCases {
            let data: [[String: Any]] = records.compactMap { r in
                guard let v = metric.value(from: r) else { return nil }
                return ["date": dayString(r.day), "qty": v]
            }
            if !data.isEmpty {
                metrics.append(["name": metric.exportName, "units": metric.exportUnits, "data": data])
            }
        }

        let sleep: [[String: Any]] = records.compactMap { r in
            // Emit a sleep record only when there's an asleep total or a stage.
            guard r.sleepH != nil || r.deepH != nil || r.remH != nil else { return nil }
            var rec: [String: Any] = ["sleepEnd": dayString(r.day)]
            if let v = r.sleepH { rec["totalSleep"] = v }
            if let v = r.deepH { rec["deep"] = v }
            if let v = r.coreH { rec["core"] = v }
            if let v = r.remH { rec["rem"] = v }
            if let v = r.awakeH { rec["awake"] = v }
            return rec
        }
        if !sleep.isEmpty {
            metrics.append(["name": "sleep_analysis", "data": sleep])
        }

        return ["data": ["metrics": metrics]]
    }

    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    static func dayString(_ d: Date) -> String { dayFormatter.string(from: d) }
}

// MARK: - Sync orchestrator

/// Reads recovery deltas, assembles, POSTs, and advances anchors only after the
/// server confirms. Registers observer queries + hourly background delivery, and
/// exposes a foreground/manual `sync()`. Self-contained auth (reads the same
/// UserDefaults/Keychain AuthModel writes) so it works from a background wake-up.
@MainActor
final class NativeHealthSync: ObservableObject {
    static let shared = NativeHealthSync()

    private let store = HKHealthStore()
    private let reader = HealthKitReader()
    private var observers: [HKObserverQuery] = []
    private var syncing = false

    @Published private(set) var lastSync: Date?
    @Published private(set) var lastError: String?

    private init() {}

    private var baseURL: URL? {
        UserDefaults.standard.string(forKey: "iron.serverURL").flatMap(URL.init(string:))
    }
    private var bearer: String? { Keychain.get("bearer") }

    /// Register one observer + hourly background delivery per read type. Idempotent
    /// (guarded), safe to call before auth — the handler no-ops until signed in and
    /// delivery activates once HealthKit access is granted. Call at app launch and
    /// again right after the user grants access.
    func registerBackgroundDelivery() {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        for type in HealthKitAuthorizer.readTypes {
            guard let sampleType = type as? HKSampleType else { continue }
            if observers.isEmpty {  // register observers once
                let observer = HKObserverQuery(sampleType: sampleType, predicate: nil) { [weak self] _, completion, _ in
                    Task { @MainActor in
                        defer { completion() }   // ALWAYS — 3 misses disables delivery
                        await self?.sync()
                    }
                }
                store.execute(observer)
                observers.append(observer)
            }
            // (Re)enable delivery — cheap and needed once access is granted.
            store.enableBackgroundDelivery(for: type, frequency: .hourly) { _, _ in }
        }
    }

    /// Delta-read all recovery types → assemble → POST → commit anchors on success.
    /// A failed POST leaves anchors un-advanced, so the same delta re-sends next
    /// time (at-least-once; the backend upsert dedupes).
    func sync() async {
        guard !syncing, let baseURL, let bearer else { return }
        syncing = true
        defer { syncing = false }
        do {
            var quantitySamples: [QuantitySample] = []
            var anchors: [(QuantityMetric, HKQueryAnchor)] = []
            for metric in QuantityMetric.allCases {
                let delta = try await reader.readQuantity(metric)
                quantitySamples += delta.samples
                anchors.append((metric, delta.newAnchor))
            }
            let sleepDelta = try await reader.readSleep()

            let records = NightAssembler.assemble(sleep: sleepDelta.samples, quantities: quantitySamples)
            if !records.isEmpty {
                try await HealthIngestClient(baseURL: baseURL, bearer: bearer).post(records)
            }

            // Success (or nothing new) → advance anchors past what we've handled.
            for (metric, anchor) in anchors { reader.commit(anchor: anchor, for: metric) }
            reader.commitSleep(anchor: sleepDelta.newAnchor)

            lastSync = Date()
            lastError = nil
        } catch {
            lastError = error.localizedDescription   // anchors untouched → retry next trigger
        }
    }
}
