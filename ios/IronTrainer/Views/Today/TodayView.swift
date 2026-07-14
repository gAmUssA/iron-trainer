import SwiftUI

/// The home screen once a plan is loaded: race countdown, today's session(s)
/// with the interval profile, tomorrow's peek, and a link to the full plan.
struct TodayView: View {
    @EnvironmentObject private var auth: AuthModel
    @EnvironmentObject private var model: ImportModel
    let plan: TrainingPlan

    private enum CheckinState: Equatable { case idle, running, failed(String) }
    @State private var checkinState: CheckinState = .idle
    @State private var story: [String]?
    @State private var readiness: ReadinessToday?

    private var todayKey: String { Self.isoDay(.now) }
    private var tomorrowKey: String {
        Self.isoDay(Calendar.current.date(byAdding: .day, value: 1, to: .now) ?? .now)
    }
    private var todaysWorkouts: [ItwWorkout] { plan.workouts.filter { $0.date == todayKey } }
    private var tomorrowsWorkouts: [ItwWorkout] { plan.workouts.filter { $0.date == tomorrowKey } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let meta = plan.meta, let race = meta.raceDay {
                    RaceCountdownHeader(name: meta.raceName ?? "Race day", raceDay: race)
                }

                if let readiness, readiness.status == "ok" {
                    ReadinessBanner(readiness: readiness)
                }

                if let hero = todaysWorkouts.first {
                    Text("Today").font(.title3).fontWeight(.semibold)
                    WorkoutHeroCard(workout: hero)
                    ForEach(Array(todaysWorkouts.dropFirst().enumerated()), id: \.offset) { _, w in
                        CompactWorkoutRow(workout: w)
                    }
                } else {
                    RestDayCard(nextSession: nextSession())
                }

                if let tomorrow = tomorrowsWorkouts.first {
                    Text("Tomorrow").font(.headline).foregroundStyle(.secondary)
                    CompactWorkoutRow(workout: tomorrow)
                }

                NavigationLink(value: PlanRoute.fullPlan) {
                    HStack {
                        Label("View full plan", systemImage: "list.bullet.rectangle")
                        Spacer()
                        Text("\(plan.workouts.count) workouts")
                            .font(.caption).foregroundStyle(.secondary)
                        Image(systemName: "chevron.right")
                            .font(.caption).foregroundStyle(.tertiary)
                    }
                    .padding(14)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)

                // Gate on what runCheckin actually needs: Keychain can hold a
                // token while UserDefaults lost the server URL (seen live when
                // reinstalling) — don't render a button that can't run.
                if auth.isSignedIn && auth.serverURL != nil {
                    Button(action: runCheckin) {
                        HStack {
                            Label(checkinState == .running ? "Checking in…" : "Weekly Check-in",
                                  systemImage: "checkmark.arrow.trianglehead.counterclockwise")
                            Spacer()
                            if checkinState == .running {
                                ProgressView().controlSize(.small)
                            } else {
                                Image(systemName: "chevron.right")
                                    .font(.caption).foregroundStyle(.tertiary)
                            }
                        }
                        .padding(14)
                        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(checkinState == .running)
                    if case let .failed(msg) = checkinState {
                        Text("Check-in failed: \(msg)")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .padding()
        }
        .sheet(isPresented: .init(get: { story != nil }, set: { if !$0 { story = nil } })) {
            CheckinStorySheet(lines: story ?? [])
        }
        .task { await resumeActiveCheckin() }
        .task { await loadReadiness() }
    }

    /// Same loop as the web card: the backend syncs, reconciles and replans,
    /// then we re-fetch the plan (which also rewrites the widget snapshot).
    /// checkin() submits a background job and polls it — short requests that
    /// survive backgrounding, instead of one fragile 90s connection.
    @MainActor
    private func runCheckin() {
        guard let server = auth.serverURL, let bearer = auth.bearer else {
            checkinState = .failed("Not connected — re-pair in Settings.")
            return
        }
        checkinState = .running
        Task {
            do {
                let source = PlanNetworkSource(baseURL: server, bearer: bearer)
                let result = try await source.checkin()
                await model.refreshPlanQuietly(from: source)  // plan + widgets, no .loading flash
                story = result.story
                checkinState = .idle
            } catch {
                checkinState = .failed(error.localizedDescription)
            }
        }
    }

    /// A check-in might already be running — started on the web, or here
    /// before the app was killed. Re-attach and present its story instead of
    /// pretending nothing is happening.
    @MainActor
    private func resumeActiveCheckin() async {
        guard checkinState == .idle,
              let server = auth.serverURL, let bearer = auth.bearer else { return }
        let source = PlanNetworkSource(baseURL: server, bearer: bearer)
        guard let jobID = await source.activeCheckinJobID() else { return }
        guard checkinState == .idle else { return }  // user tapped meanwhile
        checkinState = .running
        do {
            let result = try await source.pollCheckinJob(id: jobID)
            await model.refreshPlanQuietly(from: source)
            story = result.story
            checkinState = .idle
        } catch is CancellationError {
            checkinState = .idle  // left the screen — the job continues server-side
        } catch {
            checkinState = .failed(error.localizedDescription)
        }
    }

    /// Fetch today's readiness call. Best-effort — no banner on failure.
    @MainActor
    private func loadReadiness() async {
        guard let server = auth.serverURL, let bearer = auth.bearer else { return }
        let source = PlanNetworkSource(baseURL: server, bearer: bearer)
        readiness = await source.readinessToday()
    }

    /// The next planned session after today, for the rest-day card.
    private func nextSession() -> ItwWorkout? {
        plan.workouts
            .filter { ($0.date ?? "") > todayKey }
            .min { ($0.date ?? "") < ($1.date ?? "") }
    }

    static func isoDay(_ date: Date) -> String {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }
}

/// Today's readiness call: GO HARD / GO EASY / REST with the one-line why.
/// Same signal-not-noise rule as the web — green stays quiet-looking, amber
/// and red draw the eye.
private struct ReadinessBanner: View {
    let readiness: ReadinessToday

    private var label: String {
        switch readiness.call {
        case "hard": "GO HARD"
        case "easy": "GO EASY"
        case "rest": "REST"
        default: (readiness.call ?? "").uppercased()
        }
    }

    private var tint: Color {
        switch readiness.level {
        case "red": .red
        case "amber": .orange
        default: .green
        }
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(label)
                .font(.caption.weight(.bold))
                .monospaced()
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(tint.opacity(0.16), in: Capsule())
                .foregroundStyle(tint)
            Text(readiness.reasons.first ?? "")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .strokeBorder(tint.opacity(readiness.level == "green" ? 0 : 0.35), lineWidth: 1)
        )
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Today's readiness: \(label). \(readiness.reasons.first ?? "")")
    }
}

/// The check-in story, straight from the backend — same lines the web card shows.
private struct CheckinStorySheet: View {
    @Environment(\.dismiss) private var dismiss
    let lines: [String]

    var body: some View {
        NavigationStack {
            List {
                ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                    Label(line, systemImage: "checkmark.circle")
                        .font(.subheadline)
                }
            }
            .navigationTitle("Weekly Check-in")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// Navigation routes for value-based pushes (full list, workout detail).
/// Pushing — rather than swapping the root via ImportModel state — is what
/// gives the detail screen a system back button.
enum PlanRoute: Hashable {
    case fullPlan
    case workout(ItwWorkout)
}

private struct RaceCountdownHeader: View {
    let name: String
    let raceDay: Date

    private var daysToGo: Int {
        Calendar.current.dateComponents(
            [.day],
            from: Calendar.current.startOfDay(for: .now),
            to: Calendar.current.startOfDay(for: raceDay)
        ).day ?? 0
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(.headline)
                Text(raceDay.formatted(date: .abbreviated, time: .omitted))
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if daysToGo > 0 {
                VStack(alignment: .trailing, spacing: 0) {
                    Text("\(daysToGo)")
                        .font(.system(size: 34, weight: .bold, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(SportStyle.accent)
                    Text("days to go").font(.caption2).foregroundStyle(.secondary)
                }
            } else if daysToGo == 0 {
                Text("RACE DAY").font(.headline).fontWeight(.heavy)
                    .foregroundStyle(SportStyle.accent)
            } else {
                Text("Raced \(-daysToGo)d ago").font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(14)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .combine)
    }
}

private struct RestDayCard: View {
    let nextSession: ItwWorkout?

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "moon.zzz.fill")
                .font(.system(size: 36))
                .foregroundStyle(.secondary)
            Text("Rest day — recover well").font(.headline)
            if let next = nextSession, let date = next.date {
                Text("Next up: \(next.title ?? next.sport ?? "workout") on \(date)")
                    .font(.subheadline).foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
    }
}

/// A one-line workout row (extra sessions today, tomorrow's peek) that pushes
/// the workout detail.
struct CompactWorkoutRow: View {
    let workout: ItwWorkout

    var body: some View {
        NavigationLink(value: PlanRoute.workout(workout)) {
            HStack(spacing: 12) {
                Image(systemName: SportStyle.icon(for: workout.sport))
                    .foregroundStyle(SportStyle.color(for: workout.sport))
                    .frame(width: 26)
                VStack(alignment: .leading, spacing: 1) {
                    Text(workout.title ?? workout.sport ?? "Workout").font(.subheadline)
                    if let dur = workout.durationS {
                        Text("\(dur / 60) min").font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
            }
            .padding(12)
            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
