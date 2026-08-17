import Foundation

/// App Group I/O for the widget snapshot. Writes are atomic; a missing or
/// unreadable file is simply "no data" (the widget shows its open-the-app state).
enum SharedStore {
    static let appGroupID = "group.io.gamov.irontrainer"
    private static let filename = "snapshot.json"

    private static var url: URL? {
        FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroupID)?
            .appendingPathComponent(filename)
    }

    @discardableResult
    static func write(_ snapshot: WidgetSnapshot) -> Bool {
        guard let url else { return false }
        do {
            let data = try JSONEncoder().encode(snapshot)
            try data.write(to: url, options: .atomic)
            return true
        } catch {
            return false
        }
    }

    static func read() -> WidgetSnapshot? {
        guard let url, let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(WidgetSnapshot.self, from: data)
    }

    /// Drop the snapshot on sign-out / re-pair. The App Group file is readable
    /// with no authentication, so leaving one athlete's workouts in it would
    /// keep them on the home screen for whoever pairs the device next.
    static func clear() {
        guard let url else { return }
        try? FileManager.default.removeItem(at: url)
    }
}
