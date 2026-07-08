import SwiftUI
import WidgetKit

/// Days-to-race on the home screen and lock screen. Needs only the fixed race
/// date, so it keeps counting even if the app is never opened again.
struct RaceCountdownWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "RaceCountdown", provider: SnapshotProvider()) { entry in
            RaceCountdownView(entry: entry)
                .containerBackground(for: .widget) { Color.clear }
        }
        .configurationDisplayName("Race Countdown")
        .description("Days until race day.")
        .supportedFamilies([.systemSmall, .accessoryInline, .accessoryCircular])
    }
}

private struct RaceCountdownView: View {
    @Environment(\.widgetFamily) private var family
    let entry: SnapshotEntry

    var body: some View {
        if let days = entry.daysToRace {
            switch family {
            case .accessoryInline:
                Text(inlineText(days: days))
            case .accessoryCircular:
                VStack(spacing: 0) {
                    Text(days >= 0 ? "\(days)" : "🏁")
                        .font(.system(.title2, design: .rounded).weight(.bold))
                        .monospacedDigit()
                    Text(days >= 0 ? dayWord(days) : "done").font(.caption2)
                }
            default:
                VStack(alignment: .leading, spacing: 4) {
                    Image(systemName: "flag.checkered")
                        .font(.caption)
                        .foregroundStyle(SportStyle.accent)
                    Spacer(minLength: 0)
                    Text(days > 0 ? "\(days)" : days == 0 ? "GO!" : "🏁")
                        .font(.system(size: 44, weight: .bold, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(SportStyle.accent)
                        .minimumScaleFactor(0.5)
                    Text(days > 0 ? "\(dayWord(days)) to go"
                         : days == 0 ? "race day" : "raced \(-days) \(dayWord(-days)) ago")
                        .font(.caption).foregroundStyle(.secondary)
                    Text(entry.snapshot?.raceName ?? "")
                        .font(.caption2).foregroundStyle(.tertiary)
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            }
        } else {
            NoDataView(family: family)
        }
    }

    private func inlineText(days: Int) -> String {
        if days > 0 { return "\(shortName) — \(days) \(dayWord(days))" }
        if days == 0 { return "\(shortName) — race day!" }
        return "\(shortName) — raced \(-days) \(dayWord(-days)) ago"
    }

    private func dayWord(_ n: Int) -> String { abs(n) == 1 ? "day" : "days" }

    private var shortName: String {
        entry.snapshot?.raceName?.replacingOccurrences(of: "IRONMAN", with: "IM") ?? "Race"
    }
}

struct NoDataView: View {
    let family: WidgetFamily

    var body: some View {
        if family == .accessoryInline {
            Text("Open Iron Trainer to sync")
        } else {
            VStack(spacing: 4) {
                Image(systemName: "arrow.triangle.2.circlepath")
                    .foregroundStyle(.secondary)
                Text("Open Iron Trainer\nto sync your plan")
                    .font(.caption2)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
