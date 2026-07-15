import Foundation
import UserNotifications

/// Local-notification scheduling — no APNs, no server. Everything is computed
/// from data already on the device, in keeping with the no-background-Strava
/// posture: the phone reminds; the athlete acts.
enum Notifications {
    static let checkinReminderID = "weekly-checkin-reminder"

    /// Ask for permission if we never have; returns whether alerts are allowed.
    static func ensureAuthorized() async -> Bool {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            return true
        case .notDetermined:
            return (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        default:
            return false
        }
    }

    /// Weekly check-in reminder, Monday 08:00 local, repeating.
    static func scheduleCheckinReminder() async -> Bool {
        guard await ensureAuthorized() else { return false }
        let content = UNMutableNotificationContent()
        content.title = "Weekly Check-in"
        content.body = "Fold in last week and adapt the next one — takes one tap."
        content.sound = .default
        var comps = DateComponents()
        comps.weekday = 2  // Monday
        comps.hour = 8
        let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: true)
        let request = UNNotificationRequest(identifier: checkinReminderID,
                                            content: content, trigger: trigger)
        do {
            try await UNUserNotificationCenter.current().add(request)
            return true
        } catch {
            return false
        }
    }

    static func cancelCheckinReminder() {
        UNUserNotificationCenter.current()
            .removePendingNotificationRequests(withIdentifiers: [checkinReminderID])
    }

    // ── Morning brief ─────────────────────────────────────────────────────────

    static let briefEnabledKey = "morningBriefEnabled"
    private static let briefIDPrefix = "morning-brief-"

    /// Schedule the next 7 mornings from the cached plan: today's session,
    /// the race countdown, and a fuel line when the workout carries one.
    /// Content is computed at scheduling time from on-device data and
    /// re-scheduled on every plan refresh — no server, no background fetch.
    static func rescheduleMorningBriefs(from plan: TrainingPlan) async {
        await cancelMorningBriefs()
        guard UserDefaults.standard.bool(forKey: briefEnabledKey),
              await ensureAuthorized() else { return }

        let center = UNUserNotificationCenter.current()
        let cal = Calendar.current
        let raceDay = plan.meta?.raceDay
        let raceName = plan.meta?.raceName ?? "race day"

        for offset in 0..<7 {
            guard let day = cal.date(byAdding: .day, value: offset, to: .now) else { continue }
            var fire = cal.dateComponents([.year, .month, .day], from: day)
            fire.hour = 6
            fire.minute = 45
            // Skip a brief whose fire time is already in the past (today, late).
            if let fireDate = cal.date(from: fire), fireDate <= .now { continue }

            let key = isoDay(day)
            let todays = plan.workouts.filter { $0.date == key }
            var lines: [String] = []
            if let w = todays.first {
                let mins = (w.durationS ?? 0) / 60
                let extra = todays.count > 1 ? " (+\(todays.count - 1) more)" : ""
                lines.append("Today: \(w.title ?? w.sport ?? "Session")\(mins > 0 ? " · \(mins) min" : "")\(extra).")
                if let fuel = FuelParser.fuelLine(from: w.description) {
                    lines.append(fuel)
                }
            } else {
                lines.append("Rest day — recovery is where the adaptation happens.")
            }
            if let raceDay {
                let days = max(0, cal.dateComponents([.day], from: cal.startOfDay(for: day),
                                                     to: raceDay).day ?? 0)
                lines.append("\(days) days to \(raceName).")
            }

            let content = UNMutableNotificationContent()
            content.title = "Morning brief"
            content.body = lines.joined(separator: " ")
            content.sound = .default
            let trigger = UNCalendarNotificationTrigger(dateMatching: fire, repeats: false)
            try? await center.add(UNNotificationRequest(
                identifier: briefIDPrefix + key, content: content, trigger: trigger))
        }
    }

    /// Local-calendar ISO day — same rule as ItwWorkout date matching.
    private static func isoDay(_ date: Date) -> String {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    static func cancelMorningBriefs() async {
        let center = UNUserNotificationCenter.current()
        let pending = await center.pendingNotificationRequests()
        let ids = pending.map(\.identifier).filter { $0.hasPrefix(briefIDPrefix) }
        if !ids.isEmpty {
            center.removePendingNotificationRequests(withIdentifiers: ids)
        }
    }
}
