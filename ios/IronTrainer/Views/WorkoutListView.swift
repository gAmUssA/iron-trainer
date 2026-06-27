import SwiftUI

/// The fetched plan as a list. Tap a workout to open the date-picker preview, or
/// schedule everything in the next 7 days at once.
struct WorkoutListView: View {
    @EnvironmentObject private var model: ImportModel
    let workouts: [ItwWorkout]
    @State private var working = false

    var body: some View {
        List {
            Section {
                ForEach(Array(workouts.enumerated()), id: \.offset) { _, w in
                    Button { model.select(w) } label: { Row(workout: w) }
                        .buttonStyle(.plain)
                }
            } footer: {
                Text("Tap a workout to pick a date, or schedule the next 7 days below.")
            }
        }
        .navigationTitle("Your plan")
        .safeAreaInset(edge: .bottom) {
            Button {
                working = true
                Task { await model.scheduleAllWithinWindow(workouts); working = false }
            } label: {
                Label(working ? "Scheduling…" : "Schedule next 7 days",
                      systemImage: "calendar.badge.plus")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(working)
            .padding()
        }
    }
}

private struct Row: View {
    let workout: ItwWorkout
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon).foregroundStyle(.secondary).frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(workout.title ?? workout.sport ?? "Workout").font(.body)
                HStack(spacing: 6) {
                    if let d = workout.date { Text(d) }
                    if let dur = workout.durationS { Text("· \(dur / 60) min") }
                }
                .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
        }
        .contentShape(Rectangle())
    }

    private var icon: String {
        switch workout.sport {
        case "Swim": return "figure.pool.swim"
        case "Bike", "Brick": return "figure.outdoor.cycle"
        case "Run": return "figure.run"
        case "Strength": return "figure.strengthtraining.traditional"
        default: return "figure.mixed.cardio"
        }
    }
}
