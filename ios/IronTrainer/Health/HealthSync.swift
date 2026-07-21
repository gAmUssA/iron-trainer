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

    /// POST the payload; returns the number of days the server actually stored.
    /// The backend answers HTTP 200 `{ok:true, days:0}` when it persists nothing
    /// (e.g. a stale/revoked bearer is swallowed per-day), so callers must check
    /// the count — a 2xx alone does not mean the data landed.
    @discardableResult
    func post(_ records: [DailyRecovery]) async throws -> Int {
        var req = URLRequest(url: baseURL.appending(path: "/api/health/ingest"))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        req.httpBody = try JSONSerialization.data(withJSONObject: Self.payload(records))
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw NetworkError.http((resp as? HTTPURLResponse)?.statusCode ?? -1)
        }
        return (try? JSONDecoder().decode(IngestAck.self, from: data))?.days ?? 0
    }

    private struct IngestAck: Decodable { let days: Int? }

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

    /// How many recent days each sync re-reads and re-sends. Overlapping re-sends
    /// (like HAE's) make the sync self-healing: a failed/no-op POST, an edit, or a
    /// deletion within the window is corrected on the next sync. The backend upsert
    /// dedupes. Bounds cost — a week of samples is tiny.
    private let syncWindowDays = 7

    private let store = HKHealthStore()
    private let reader = HealthKitReader()
    private var observers: [HKObserverQuery] = []

    @Published private(set) var isSyncing = false
    @Published private(set) var lastSync: Date?
    @Published private(set) var lastError: String?

    private init() {}

    private var baseURL: URL? {
        UserDefaults.standard.string(forKey: AuthModel.serverKey).flatMap(URL.init(string:))
    }
    private var bearer: String? { Keychain.get(AuthModel.tokenAccount) }

    /// Register ONE observer + hourly background delivery PER read type. Observers
    /// are created once (guarded on the whole set, not per iteration); background
    /// delivery is re-enabled on every call so it activates once the user grants
    /// access. Safe to call before auth — the handler no-ops until signed in.
    func registerBackgroundDelivery() {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        let sampleTypes = HealthKitAuthorizer.readTypes.compactMap { $0 as? HKSampleType }
        if observers.isEmpty {
            for sampleType in sampleTypes {
                let observer = HKObserverQuery(sampleType: sampleType, predicate: nil) { [weak self] _, completion, _ in
                    Task { @MainActor in
                        defer { completion() }   // ALWAYS — 3 misses disables delivery
                        await self?.sync()
                    }
                }
                store.execute(observer)
                observers.append(observer)
            }
        }
        for sampleType in sampleTypes {
            store.enableBackgroundDelivery(for: sampleType, frequency: .hourly) { _, _ in }
        }
    }

    /// Read the recent window fully → assemble → POST. Idempotent and self-healing:
    /// no anchors are advanced, so nothing is ever consumed-past-unsent. A 2xx that
    /// stored 0 days (revoked bearer) is treated as a failure, not success, and the
    /// next sync retries the same window.
    func sync() async {
        guard !isSyncing, let baseURL, let bearer else { return }
        isSyncing = true
        defer { isSyncing = false }
        do {
            let (quantities, sleep) = try await reader.recentSamples(days: syncWindowDays)
            let records = NightAssembler.assemble(sleep: sleep, quantities: quantities)
            guard !records.isEmpty else {   // nothing to send — not an error
                lastSync = Date()
                lastError = nil
                return
            }
            let stored = try await HealthIngestClient(baseURL: baseURL, bearer: bearer).post(records)
            if stored == 0 {
                lastError = "Server stored no data — your sign-in may have expired."
            } else {
                lastSync = Date()
                lastError = nil
            }
        } catch {
            lastError = error.localizedDescription
        }
    }
}
