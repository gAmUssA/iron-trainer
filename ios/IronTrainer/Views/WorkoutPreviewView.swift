import SwiftUI

/// Shows the parsed .itw workout and offers "Schedule to date".
struct WorkoutPreviewView: View {
    @EnvironmentObject private var model: ImportModel
    @AppStorage(DistanceUnit.storageKey) private var unit: DistanceUnit = .km
    let workout: ItwWorkout
    @State private var working = false
    @State private var scheduleDate: Date

    init(workout: ItwWorkout) {
        self.workout = workout
        _scheduleDate = State(initialValue: WorkoutScheduling.defaultDate(for: workout))
    }

    /// True when the workout's own planned date is too far out to schedule, so we
    /// had to pre-select a closer day.
    private var plannedOutOfWindow: Bool {
        workout.date != nil && !WorkoutScheduling.plannedDateIsSchedulable(workout)
    }

    var body: some View {
        List {
            Section {
                LabeledContent("Sport", value: workout.sport ?? "—")
                if let date = workout.date { LabeledContent("Planned", value: date) }
                if let dur = workout.durationS { LabeledContent("Duration", value: format(seconds: dur)) }
                if let dist = workout.distanceM {
                    LabeledContent("Distance",
                                   value: UnitFormat.distance(meters: dist, unit: unit, sport: workout.sport))
                }
                if let desc = workout.description, !desc.isEmpty {
                    Text(desc).font(.subheadline).foregroundStyle(.secondary)
                }
            } header: {
                Text(workout.title ?? "Workout")
            }

            Section {
                DatePicker("Schedule for",
                           selection: $scheduleDate,
                           in: WorkoutScheduling.window,
                           displayedComponents: .date)
            } header: {
                Text("Schedule")
            } footer: {
                Text(plannedOutOfWindow
                     ? "Apple Watch only accepts workouts within the next 7 days, so the planned date was moved into range — adjust it if you like."
                     : "Apple Watch only accepts workouts within the next 7 days.")
            }

            Section("Steps") {
                ForEach(Array(workout.steps.enumerated()), id: \.offset) { _, step in
                    StepRow(step: step, sport: workout.sport, unit: unit)
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            Button {
                working = true
                Task { await model.schedule(workout, on: scheduleDate); working = false }
            } label: {
                Label(working ? "Scheduling…" : "Schedule to Apple Watch",
                      systemImage: "calendar.badge.plus")
                    .frame(maxWidth: .infinity)
            }
            .primaryActionButtonStyle()
            .disabled(working)
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
    let sport: String?
    let unit: DistanceUnit
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
        if let m = step.distanceM {
            return UnitFormat.distance(meters: m, unit: unit, sport: sport)
        }
        return "open"
    }

    private var targetText: String? {
        guard let t = step.target, let type = t.type, type != "open",
              let lo = t.low, let hi = t.high else { return nil }
        switch type {
        case "power": return "Power \(Int(lo))–\(Int(hi)) W"
        case "hr": return "HR \(Int(lo))–\(Int(hi)) bpm"
        case "pace":
            return t.unit == "sec_per_100m"
                ? "Pace \(UnitFormat.swimPaceRange(lowSecPer100: lo, highSecPer100: hi))"
                : "Pace \(UnitFormat.paceRange(lowSecPerKm: lo, highSecPerKm: hi, unit: unit))"
        default: return nil
        }
    }
}
