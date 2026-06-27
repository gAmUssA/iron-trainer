import SwiftUI

/// Shows the parsed .itw workout and offers "Schedule to date".
struct WorkoutPreviewView: View {
    @EnvironmentObject private var model: ImportModel
    let workout: ItwWorkout
    @State private var working = false

    var body: some View {
        List {
            Section {
                LabeledContent("Sport", value: workout.sport ?? "—")
                if let date = workout.date { LabeledContent("Planned", value: date) }
                if let dur = workout.durationS { LabeledContent("Duration", value: format(seconds: dur)) }
                if let desc = workout.description, !desc.isEmpty {
                    Text(desc).font(.subheadline).foregroundStyle(.secondary)
                }
            } header: {
                Text(workout.title ?? "Workout")
            }

            Section("Steps") {
                ForEach(Array(workout.steps.enumerated()), id: \.offset) { _, step in
                    StepRow(step: step)
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            Button {
                working = true
                Task { await model.schedule(workout); working = false }
            } label: {
                Label(working ? "Scheduling…" : "Schedule to date",
                      systemImage: "calendar.badge.plus")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(working || workout.scheduleComponents == nil)
            .padding()
        }
    }

    private func format(seconds: Int) -> String {
        let m = seconds / 60
        return m >= 60 ? "\(m / 60)h \(m % 60)m" : "\(m)m"
    }
}

private struct StepRow: View {
    let step: ItwWorkout.Step
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text((step.type ?? "step").capitalized).font(.headline)
                Spacer()
                Text(goalText).font(.subheadline).foregroundStyle(.secondary)
            }
            if let t = targetText { Text(t).font(.caption).foregroundStyle(.secondary) }
            if let notes = step.notes, !notes.isEmpty {
                Text(notes).font(.caption2).foregroundStyle(.tertiary)
            }
        }
    }

    private var goalText: String {
        if let s = step.durationS { return "\(s / 60) min" }
        if let m = step.distanceM { return "\(Int(m)) m" }
        return "open"
    }

    private var targetText: String? {
        guard let t = step.target, let type = t.type, type != "open",
              let lo = t.low, let hi = t.high else { return nil }
        switch type {
        case "power": return "Power \(Int(lo))–\(Int(hi)) W"
        case "hr": return "HR \(Int(lo))–\(Int(hi)) bpm"
        case "pace": return "Pace \(Int(lo))–\(Int(hi)) s/\(t.unit == "sec_per_100m" ? "100m" : "km")"
        default: return nil
        }
    }
}
