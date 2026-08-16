import Foundation

/// On-disk copy of the last fetched training plan, so the Today view is there
/// the instant the app opens — including with no connectivity — instead of an
/// empty screen waiting on a network round-trip.
///
/// Application Support rather than Caches: the plan is server-regenerable, but
/// the entire point of keeping it is surviving a launch that has no network,
/// and iOS may purge Caches under storage pressure at exactly the wrong moment.
/// Excluded from backup for the regenerable reason — it is a few KB, so this is
/// about correctness of intent, not size.
///
/// Cleared on sign-out: a plan is athlete-specific and must not outlive the
/// session that fetched it.
enum PlanCache {
    private static let filename = "plan.json"

    private static var url: URL? {
        guard let dir = try? FileManager.default.url(for: .applicationSupportDirectory,
                                                     in: .userDomainMask,
                                                     appropriateFor: nil,
                                                     create: true) else { return nil }
        return dir.appendingPathComponent(filename)
    }

    @discardableResult
    static func write(_ plan: TrainingPlan) -> Bool {
        guard var url else { return false }
        do {
            try JSONEncoder().encode(plan).write(to: url, options: .atomic)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? url.setResourceValues(values)
            return true
        } catch {
            return false
        }
    }

    /// The cached plan, or nil when absent/unreadable/stale-shaped. A decode
    /// failure after a model change is "no cache", never a crash — the network
    /// load right behind it will repopulate.
    static func read() -> TrainingPlan? {
        guard let url, let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(TrainingPlan.self, from: data)
    }

    static func clear() {
        guard let url else { return }
        try? FileManager.default.removeItem(at: url)
    }
}
