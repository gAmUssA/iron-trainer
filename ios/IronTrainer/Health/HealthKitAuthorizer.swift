import Foundation
import HealthKit

/// Requests read access to the recovery metrics the app ingests from HealthKit
/// (epic iron-trainer-2f2c — retiring Health Auto Export).
///
/// Read authorization on iOS is deliberately opaque: `authorizationStatus`
/// reflects only *write* grants, so a denied read is indistinguishable from
/// "no data yet". We therefore NEVER gate features on status — we request
/// access, then let the readers surface an empty state that points the user at
/// Health → Sharing. The only thing worth persisting is whether we've *asked*,
/// which drives the Settings card's label.
@MainActor
final class HealthKitAuthorizer: ObservableObject {
    private let store = HKHealthStore()
    private let askedKey = "health.nativeAuthRequested"

    /// Have we presented the system read-permission sheet at least once?
    @Published private(set) var hasRequested: Bool

    init() {
        hasRequested = UserDefaults.standard.bool(forKey: askedKey)
    }

    /// HealthKit is unavailable on iPad and in some review environments.
    var isAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    /// The recovery metrics the native ingester reads. Read-only — the app
    /// writes nothing here (workout scheduling uses WorkoutKit, not HK writes).
    static let readTypes: Set<HKObjectType> = {
        var types: Set<HKObjectType> = [HKCategoryType(.sleepAnalysis)]
        let quantities: [HKQuantityTypeIdentifier] = [
            .heartRateVariabilitySDNN,       // sleep-window mean of spot samples
            .restingHeartRate,               // Apple delete-and-replaces estimates
            .respiratoryRate,
            .appleSleepingWristTemperature,  // Apple Watch only
            .bodyMass,
            .vo2Max,                         // Garmin does not sync this to Health
        ]
        for id in quantities { types.insert(HKQuantityType(id)) }
        return types
    }()

    /// Present the system read-permission sheet. Resolves once the user
    /// responds (or immediately, if already granted). Does NOT throw on
    /// denial — a denied read is invisible by design; only a genuinely
    /// unavailable store or a request error throws.
    func requestAuthorization() async throws {
        guard isAvailable else { throw HealthKitError.unavailable }
        try await store.requestAuthorization(toShare: [], read: Self.readTypes)
        hasRequested = true
        UserDefaults.standard.set(true, forKey: askedKey)
    }
}

enum HealthKitError: LocalizedError {
    case unavailable

    var errorDescription: String? {
        switch self {
        case .unavailable: "Apple Health isn’t available on this device."
        }
    }
}
