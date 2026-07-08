import WidgetKit

/// One provider for both widgets: reads the App Group snapshot (no network, no
/// model code) and emits an entry for now plus one per upcoming local midnight
/// covered by the snapshot, so "today" rolls over and the countdown decrements
/// without any reloads beyond the app-driven ones.
struct SnapshotEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot?

    /// The snapshot day matching this entry's date, if the snapshot covers it.
    var day: WidgetSnapshot.Day? {
        guard let snapshot else { return nil }
        let key = Self.isoDay(date)
        return snapshot.days.first { $0.date == key }
    }

    /// Days from this entry's date to the race, from the fixed race date —
    /// works even when the workout snapshot has gone stale.
    var daysToRace: Int? {
        guard let raceDate = snapshot?.raceDate else { return nil }
        let parts = raceDate.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        var comps = DateComponents()
        comps.year = parts[0]; comps.month = parts[1]; comps.day = parts[2]
        guard let race = Calendar.current.date(from: comps) else { return nil }
        return Calendar.current.dateComponents(
            [.day],
            from: Calendar.current.startOfDay(for: date),
            to: Calendar.current.startOfDay(for: race)
        ).day
    }

    static func isoDay(_ date: Date) -> String {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }
}

struct SnapshotProvider: TimelineProvider {
    func placeholder(in context: Context) -> SnapshotEntry {
        SnapshotEntry(date: .now, snapshot: .sample)
    }

    func getSnapshot(in context: Context, completion: @escaping (SnapshotEntry) -> Void) {
        let snapshot = context.isPreview ? .sample : SharedStore.read()
        completion(SnapshotEntry(date: .now, snapshot: snapshot))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SnapshotEntry>) -> Void) {
        let snapshot = SharedStore.read()
        guard let snapshot else {
            // No data yet: a single already-past entry with .atEnd would make
            // WidgetKit re-request immediately (throttled → blank widget).
            // Check back later instead; the app also force-reloads on plan load.
            let retry = Date.now.addingTimeInterval(15 * 60)
            completion(Timeline(entries: [SnapshotEntry(date: .now, snapshot: nil)],
                                policy: .after(retry)))
            return
        }
        var entries = [SnapshotEntry(date: .now, snapshot: snapshot)]
        let cal = Calendar.current
        var midnight = cal.startOfDay(for: .now)
        for _ in 0..<7 {
            guard let next = cal.date(byAdding: .day, value: 1, to: midnight) else { break }
            midnight = next
            entries.append(SnapshotEntry(date: midnight, snapshot: snapshot))
        }
        completion(Timeline(entries: entries, policy: .atEnd))
    }
}
