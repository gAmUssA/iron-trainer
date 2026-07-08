import SwiftUI
import WidgetKit

/// Today's planned session at a glance; the medium size adds the interval
/// profile and fueling line.
struct TodayWorkoutWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "TodayWorkout", provider: SnapshotProvider()) { entry in
            TodayWorkoutView(entry: entry)
                .containerBackground(for: .widget) { Color.clear }
        }
        .configurationDisplayName("Today's Workout")
        .description("Your planned session for today.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

private struct TodayWorkoutView: View {
    @Environment(\.widgetFamily) private var family
    let entry: SnapshotEntry

    var body: some View {
        if entry.snapshot == nil {
            NoDataView(family: family)
        } else if let day = entry.day {
            if let workout = day.workouts.first {
                workoutView(workout)
            } else {
                restView
            }
        } else {
            // Snapshot exists but doesn't cover this date — data went stale.
            NoDataView(family: family)
        }
    }

    @ViewBuilder
    private func workoutView(_ w: WidgetSnapshot.WorkoutSummary) -> some View {
        let color = SportStyle.color(for: w.sport)
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: SportStyle.icon(for: w.sport))
                    .font(family == .systemSmall ? .body : .title3)
                    .foregroundStyle(color)
                if family != .systemSmall {
                    Text(w.sport ?? "Workout").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                if let dur = w.durationS {
                    Text(duration(dur)).font(.caption2).foregroundStyle(.secondary)
                }
            }
            Text(w.title ?? w.sport ?? "Workout")
                .font(family == .systemSmall ? .caption : .subheadline)
                .fontWeight(.semibold)
                .lineLimit(2)
            if family != .systemSmall {
                if !w.profile.isEmpty {
                    WorkoutProfileChart(segments: w.profile, sportColor: color, height: 34)
                }
                if let fuel = w.fuel {
                    Label(fuel, systemImage: "fork.knife")
                        .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
            } else if !w.profile.isEmpty {
                WorkoutProfileChart(segments: w.profile, sportColor: color, height: 26)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private var restView: some View {
        VStack(spacing: 4) {
            Image(systemName: "moon.zzz.fill").foregroundStyle(.secondary)
            Text("Rest day").font(.caption).fontWeight(.semibold)
            Text("Recover well").font(.caption2).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func duration(_ seconds: Int) -> String {
        let m = seconds / 60
        return m >= 60 ? "\(m / 60)h \(m % 60)m" : "\(m)m"
    }
}
