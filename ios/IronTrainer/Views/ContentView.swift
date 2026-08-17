import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject private var model: ImportModel
    @EnvironmentObject private var auth: AuthModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var showingPicker = false
    @State private var showingSettings = false
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                switch model.state {
                case .empty:
                    EmptyState(signedIn: auth.isSignedIn,
                               notice: model.autoLoadNotice,
                               onLoadPlan: loadPlan,
                               onImport: { showingPicker = true },
                               onConnect: { showingSettings = true })
                case .loading:
                    ProgressView("Loading…")
                case let .loaded(workout):
                    WorkoutPreviewView(workout: workout)
                case let .loadedPlan(plan):
                    TodayView(plan: plan)
                case let .scheduled(message):
                    ResultState(systemImage: "checkmark.circle.fill",
                                tint: .green, message: message,
                                primaryTitle: model.lastPlan != nil ? "Back to plan" : "Done",
                                onPrimary: { model.lastPlan != nil ? model.backToPlan() : model.reset() },
                                onSecondary: model.lastPlan != nil ? { model.reset() } : nil)
                case let .failed(message):
                    ResultState(systemImage: "exclamationmark.triangle.fill",
                                tint: .orange, message: message,
                                primaryTitle: model.lastWorkout != nil ? "Change date" : "Done",
                                onPrimary: { model.lastWorkout != nil ? model.editWorkout() : model.reset() },
                                onSecondary: model.lastWorkout != nil ? { model.reset() } : nil)
                }
            }
            .navigationTitle("Iron Trainer")
            .navigationDestination(for: PlanRoute.self) { route in
                switch route {
                case .fullPlan:
                    WorkoutListView(workouts: model.lastPlan?.workouts ?? [])
                case let .workout(w):
                    WorkoutPreviewView(workout: w)
                }
            }
            .onChange(of: model.state) {
                // A state switch replaces the root — don't leave the pushed
                // full-plan list lingering over the new screen.
                path = NavigationPath()
            }
            // The plan loads itself: once at launch, and again on every return to
            // the foreground so a phone left backgrounded overnight isn't still
            // showing yesterday as "today". ImportModel's init has already put
            // any cached plan on screen, so this is the refresh behind it.
            //
            // ONE scene-phase-driven task rather than .task + .onChange. A bool
            // gate does not work here: .task would set it before its first
            // suspension point, so a cold-start .active transition arriving after
            // that still passes the guard and fires a second, superseding GET.
            // Keying the task on scenePhase makes "active" the single trigger —
            // SwiftUI cancels and restarts it on each transition, so each
            // activation runs exactly one load.
            .task(id: scenePhase) {
                guard scenePhase == .active else { return }
                await autoLoadPlan()
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { showingSettings = true } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel("Settings")
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if auth.isSignedIn {
                        Button { loadPlan() } label: { Image(systemName: "arrow.clockwise") }
                            .accessibilityLabel("Refresh plan")
                    }
                    Button { showingPicker = true } label: { Image(systemName: "doc.badge.plus") }
                        .accessibilityLabel("Import workout file")
                }
            }
            .fileImporter(isPresented: $showingPicker,
                          allowedContentTypes: [UTType.itw, .json],
                          allowsMultipleSelection: false) { result in
                if case let .success(urls) = result, let url = urls.first {
                    Task { await model.importFrom(FileImportSource(url: url)) }
                }
            }
            .sheet(isPresented: $showingSettings) { SettingsView() }
        }
    }

    private func loadPlan() {
        guard let source = planSource() else {
            showingSettings = true
            return
        }
        Task { await model.loadPlan(from: source) }
    }

    /// The unattended load. Silent when not signed in — an automatic action must
    /// never pop Settings in the user's face on launch.
    private func autoLoadPlan() async {
        guard let source = planSource() else { return }
        await model.autoLoadPlan(from: source)
    }

    private func planSource() -> PlanNetworkSource? {
        guard let server = auth.serverURL, let bearer = auth.bearer else { return nil }
        return PlanNetworkSource(baseURL: server, bearer: bearer)
    }
}

private struct EmptyState: View {
    let signedIn: Bool
    /// Why the automatic load produced nothing. Present only when the app tried
    /// and came up empty — otherwise this really is a first run.
    let notice: String?
    let onLoadPlan: () -> Void
    let onImport: () -> Void
    let onConnect: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label("No workout yet", systemImage: "figure.run")
        } description: {
            Text(notice ?? (signedIn
                 ? "Load your training plan from Iron Trainer, or open a .itw file."
                 : "Connect to Iron Trainer to sync your plan, or open a .itw file (Files, Mail, AirDrop)."))
        } actions: {
            if signedIn {
                Button("Load my plan", action: onLoadPlan).buttonStyle(.borderedProminent)
            } else {
                Button("Connect", action: onConnect).buttonStyle(.borderedProminent)
            }
            Button("Import .itw file", action: onImport).buttonStyle(.bordered)
        }
    }
}

private struct ResultState: View {
    let systemImage: String
    let tint: Color
    let message: String
    let primaryTitle: String
    let onPrimary: () -> Void
    let onSecondary: (() -> Void)?
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: systemImage).font(.system(size: 48)).foregroundStyle(tint)
            Text(message).multilineTextAlignment(.center).padding(.horizontal)
            Button(primaryTitle, action: onPrimary).buttonStyle(.borderedProminent)
            if let onSecondary { Button("Discard", action: onSecondary).buttonStyle(.bordered) }
        }
        .padding()
    }
}

/// Exported UTI for the .itw document type (also declared in Info.plist).
extension UTType {
    static let itw = UTType(exportedAs: "io.gamov.irontrainer.itw")
}
