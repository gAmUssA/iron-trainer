import SwiftUI

/// The home screen once a plan is loaded: race countdown, today's session(s)
/// with the interval profile, tomorrow's peek, and a link to the full plan.
struct TodayView: View {
    @EnvironmentObject private var model: ImportModel
    let plan: TrainingPlan

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

                if let hero = todaysWorkouts.first {
                    Text("Today").font(.title3).fontWeight(.semibold)
                    WorkoutHeroCard(workout: hero)
                    ForEach(Array(todaysWorkouts.dropFirst().enumerated()), id: \.offset) { _, w in
                        CompactWorkoutRow(workout: w) { model.select(w) }
                    }
                } else {
                    RestDayCard(nextSession: nextSession())
                }

                if let tomorrow = tomorrowsWorkouts.first {
                    Text("Tomorrow").font(.headline).foregroundStyle(.secondary)
                    CompactWorkoutRow(workout: tomorrow) { model.select(tomorrow) }
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
            }
            .padding()
        }
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

/// Navigation route for the value-based push to the full plan list.
enum PlanRoute: Hashable {
    case fullPlan
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

/// A one-line workout row (extra sessions today, tomorrow's peek).
struct CompactWorkoutRow: View {
    let workout: ItwWorkout
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
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
