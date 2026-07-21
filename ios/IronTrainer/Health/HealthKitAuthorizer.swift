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

    /// Whether the system permission sheet would still appear — i.e. at least
    /// one type is still undecided. Once every type has been requested (granted
    /// OR denied) re-requesting is a silent no-op, so the UI must switch from a
    /// "Connect" button to "manage in Health" guidance. Sourced from
    /// `statusForAuthorizationRequest` (HealthKit's own sheet-gate), not a
    /// cached flag — so adding new read types later correctly re-arms the sheet.
    @Published private(set) var needsRequest = true

    /// HealthKit is unavailable on iPad and in some review environments.
    var isAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    /// The recovery metrics the native ingester reads. Read-only — the app
    /// writes nothing here (workout scheduling uses WorkoutKit, not HK writes).
    /// Derived from `QuantityMetric.allCases` (+ sleep) so the authorized set and
    /// the reader's set can't drift — an unauthorized read would silently return
    /// zero samples with no error (read auth is opaque).
    static let readTypes: Set<HKObjectType> = {
        var types: Set<HKObjectType> = [HKCategoryType(.sleepAnalysis)]
        for metric in QuantityMetric.allCases { types.insert(metric.type) }
        return types
    }()

    /// Refresh `needsRequest` from HealthKit — the source of truth. Swallows
    /// errors (defaults to "still needs request" so the button stays available).
    func refreshStatus() async {
        guard isAvailable else { return }
        let status = try? await store.statusForAuthorizationRequest(
            toShare: [], read: Self.readTypes
        )
        needsRequest = (status != .unnecessary)
    }

    /// Present the system read-permission sheet. Resolves once the user
    /// responds (or immediately, if already asked). Does NOT throw on
    /// denial — a denied read is invisible by design; only a genuinely
    /// unavailable store or a request error throws.
    func requestAuthorization() async throws {
        guard isAvailable else { throw HealthKitError.unavailable }
        try await store.requestAuthorization(toShare: [], read: Self.readTypes)
        await refreshStatus()
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
