import SwiftUI
import WorkoutKit

/// Today's session, front and center: sport chip, interval profile, fueling
/// line, and a one-tap Send to Watch. Scheduling here is LOCAL state — routing
/// it through ImportModel would flip the global state and dismiss this screen.
struct WorkoutHeroCard: View {
    @EnvironmentObject private var model: ImportModel
    let workout: ItwWorkout

    private enum SendState: Equatable { case idle, sending, sent, failed(String) }
    @State private var sendState: SendState = .idle
    @State private var showError = false

    private var sportColor: Color { SportStyle.color(for: workout.sport) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: SportStyle.icon(for: workout.sport))
                    .font(.title3)
                    .foregroundStyle(sportColor)
                    .frame(width: 34, height: 34)
                    .background(sportColor.opacity(0.15), in: Circle())
                VStack(alignment: .leading, spacing: 1) {
                    Text(workout.title ?? workout.sport ?? "Workout")
                        .font(.headline)
                    if let dur = workout.durationS {
                        Text(Self.format(seconds: dur))
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Button { model.select(workout) } label: {
                    Image(systemName: "info.circle")
                }
                .accessibilityLabel("Workout details")
            }

            let segments = WorkoutIntensity.segments(for: workout, athlete: workout.athlete)
            if !segments.isEmpty {
                WorkoutProfileChart(segments: segments, sportColor: sportColor)
            }

            if let fuel = FuelParser.fuelLine(from: workout.description) {
                Label(fuel, systemImage: "fork.knife")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            Button(action: send) {
                Label(buttonTitle, systemImage: buttonIcon)
                    .frame(maxWidth: .infinity)
            }
            .primaryActionButtonStyle(tint: SportStyle.accent)
            .disabled(sendState == .sending || sendState == .sent)
        }
        .padding(16)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
        .alert("Couldn't send to Watch", isPresented: $showError) {
            Button("OK", role: .cancel) { sendState = .idle }
        } message: {
            if case let .failed(msg) = sendState { Text(msg) }
        }
    }

    private var buttonTitle: String {
        switch sendState {
        case .idle, .failed: return "Send to Watch"
        case .sending: return "Sending…"
        case .sent: return "Sent — open Workout on your Watch"
        }
    }

    private var buttonIcon: String {
        sendState == .sent ? "checkmark" : "applewatch"
    }

    private func send() {
        sendState = .sending
        Task {
            do {
                try await WorkoutScheduling.schedule(
                    workout, on: WorkoutScheduling.defaultDate(for: workout))
                sendState = .sent
            } catch {
                sendState = .failed(error.localizedDescription)
                showError = true
            }
        }
    }

    static func format(seconds: Int) -> String {
        let m = seconds / 60
        return m >= 60 ? "\(m / 60)h \(m % 60)m" : "\(m)m"
    }
}
