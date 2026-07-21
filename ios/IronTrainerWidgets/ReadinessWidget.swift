import SwiftUI
import WidgetKit

/// Today's readiness/recovery at a glance: the go-hard/easy/rest call, colored by
/// level (green/amber/red), with HRV/RHR. Reads the same App Group snapshot as the
/// other widgets — the app fetches readiness and writes it, the extension only reads.
struct ReadinessWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "Readiness", provider: SnapshotProvider()) { entry in
            ReadinessView(entry: entry)
        }
        .configurationDisplayName("Readiness")
        .description("Today's go-hard / easy / rest call, with HRV and RHR.")
        .supportedFamilies([.systemSmall, .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

/// Level → color + legible foreground. Colors track the web readiness palette.
private enum ReadinessLevel {
    static func color(_ level: String?) -> Color {
        switch level {
        case "green": return Color(red: 0.290, green: 0.871, blue: 0.502) // #4ade80
        case "amber": return Color(red: 1.0, green: 0.706, blue: 0.329)   // #ffb454
        case "red":   return Color(red: 0.937, green: 0.325, blue: 0.314) // #ef5350
        default:      return Color(red: 0.294, green: 0.333, blue: 0.388) // dark slate #4b5563
        }
    }

    /// Foreground that stays readable on the level background: dark on the bright
    /// green/amber fills, white on the dark red/neutral fills.
    static func onColor(_ level: String?) -> Color {
        (level == "amber" || level == "green") ? .black : .white
    }

    /// How full to draw the accessory ring — a rough readiness fraction.
    static func ringFill(_ level: String?) -> Double {
        switch level {
        case "green": return 1.0
        case "amber": return 0.6
        case "red":   return 0.3
        default:      return 0.5
        }
    }
}

private struct ReadinessView: View {
    @Environment(\.widgetFamily) private var family
    let entry: SnapshotEntry

    /// The entry's own day (each upcoming-midnight entry shares one snapshot).
    private var entryDay: String { SnapshotEntry.isoDay(entry.date) }

    var body: some View {
        Group {
            if let r = entry.snapshot?.readiness, hasContent(r) {
                content(r, stale: r.isStale(on: entryDay))
            } else {
                placeholder
            }
        }
        .containerBackground(for: .widget) { backgroundView }
    }

    /// Show the glance if any meaningful field is present (not just call+hrv).
    private func hasContent(_ r: WidgetSnapshot.Readiness) -> Bool {
        r.call != nil || r.level != nil || r.hrvMs != nil || r.rhrBpm != nil || r.ctl != nil
    }

    // MARK: Backgrounds

    /// Level used for coloring — nil (neutral) once the call is stale, so a stale
    /// green/amber fill never reads as today's current call.
    private var displayLevel: String? {
        guard let r = entry.snapshot?.readiness, hasContent(r), !r.isStale(on: entryDay)
        else { return nil }
        return r.level
    }

    @ViewBuilder
    private var backgroundView: some View {
        switch family {
        case .systemSmall:
            Rectangle().fill(ReadinessLevel.color(displayLevel).gradient)
        case .accessoryCircular:
            AccessoryWidgetBackground()
        default:
            Color.clear
        }
    }

    // MARK: Content

    @ViewBuilder
    private func content(_ r: WidgetSnapshot.Readiness, stale: Bool) -> some View {
        let level = stale ? nil : r.level  // stale → neutral coloring
        switch family {
        case .accessoryInline:
            Text(stale
                 ? "\(callWord(r.call, caps: false)) · HRV \(intStr(r.hrvMs)) · stale"
                 : "\(callWord(r.call, caps: false)) · HRV \(intStr(r.hrvMs))")
        case .accessoryCircular:
            Gauge(value: ReadinessLevel.ringFill(level)) {
                Image(systemName: callGlyph(r.call))
            } currentValueLabel: {
                Text(shortNum(r.hrvMs))
            }
            .gaugeStyle(.accessoryCircular)
            .tint(ReadinessLevel.color(level))
            .opacity(stale ? 0.6 : 1)
        case .accessoryRectangular:
            VStack(alignment: .leading, spacing: 1) {
                Label(callWord(r.call, caps: false), systemImage: callGlyph(r.call))
                    .font(.headline)
                Text("HRV \(intStr(r.hrvMs)) · RHR \(intStr(r.rhrBpm))")
                    .font(.caption)
                if stale {
                    Text("as of \(shortDate(r.day)) · open to refresh")
                        .font(.caption2).foregroundStyle(.secondary)
                } else if let reason = r.reason, !reason.isEmpty {
                    Text(reason)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                        .minimumScaleFactor(0.85)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .opacity(stale ? 0.7 : 1)
        default:
            smallView(r, stale: stale, level: level)
        }
    }

    private func smallView(_ r: WidgetSnapshot.Readiness, stale: Bool, level: String?) -> some View {
        let fg = ReadinessLevel.onColor(level)
        return VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(stale ? "AS OF \(shortDate(r.day).uppercased())" : "READINESS")
                    .font(.caption2).fontWeight(.bold)
                    .foregroundStyle(fg.opacity(0.75))
                    .minimumScaleFactor(0.7).lineLimit(1)
                Spacer()
                if let sport = todaySport {
                    Image(systemName: SportStyle.icon(for: sport)).font(.caption2)
                }
            }
            Spacer(minLength: 0)
            HStack(spacing: 6) {
                Image(systemName: callGlyph(r.call))
                Text(callWord(r.call, caps: true))
                    .font(.system(size: 30, weight: .heavy, design: .rounded))
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            HStack(spacing: 12) {
                metric("HRV", intStr(r.hrvMs))
                metric("RHR", intStr(r.rhrBpm))
            }
        }
        .foregroundStyle(fg)
        .opacity(stale ? 0.85 : 1)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private func metric(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(value).font(.headline).monospacedDigit()
            Text(label).font(.caption2).opacity(0.75)
        }
    }

    private var placeholder: some View {
        if family == .accessoryInline {
            return AnyView(Text("Open Iron Trainer to sync"))
        }
        return AnyView(
            VStack(spacing: 4) {
                Image(systemName: "heart.text.square").foregroundStyle(.secondary)
                Text("No readiness yet")
                    .font(.caption2).multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        )
    }

    // MARK: Helpers

    private var todaySport: String? { entry.day?.workouts.first?.sport }

    private func callWord(_ call: String?, caps: Bool) -> String {
        let word: String
        switch call {
        case "hard": word = "Hard"
        case "easy": word = "Easy"
        case "rest": word = "Rest"
        default:     word = "—"
        }
        return caps ? word.uppercased() : word
    }

    private func callGlyph(_ call: String?) -> String {
        switch call {
        case "hard": return "bolt.fill"
        case "easy": return "figure.walk"
        case "rest": return "moon.zzz.fill"
        default:     return "questionmark"
        }
    }

    /// Whole-number string for a metric, or "–" when absent.
    private func intStr(_ v: Double?) -> String {
        guard let v else { return "–" }
        return String(Int(v.rounded()))
    }

    /// Compact number for the tight circular gauge (blank when absent).
    private func shortNum(_ v: Double?) -> String {
        guard let v else { return "" }
        return String(Int(v.rounded()))
    }

    /// "2026-07-20" → "Jul 20" for the stale marker.
    private func shortDate(_ ymd: String?) -> String {
        guard let ymd, let d = Self.ymdParser.date(from: ymd) else { return "earlier" }
        return Self.monthDay.string(from: d)
    }

    private static let ymdParser: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    private static let monthDay: DateFormatter = {
        let f = DateFormatter()
        f.setLocalizedDateFormatFromTemplate("MMMd")
        return f
    }()
}
