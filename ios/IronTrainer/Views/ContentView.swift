import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject private var model: ImportModel
    @EnvironmentObject private var auth: AuthModel
    @State private var showingPicker = false
    @State private var showingSettings = false

    var body: some View {
        NavigationStack {
            Group {
                switch model.state {
                case .empty:
                    EmptyState(signedIn: auth.isSignedIn,
                               onLoadPlan: loadPlan,
                               onImport: { showingPicker = true },
                               onConnect: { showingSettings = true })
                case .loading:
                    ProgressView("Loading…")
                case let .loaded(workout):
                    WorkoutPreviewView(workout: workout)
                case let .loadedPlan(workouts):
                    WorkoutListView(workouts: workouts)
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
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { showingSettings = true } label: { Image(systemName: "gearshape") }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if auth.isSignedIn {
                        Button { loadPlan() } label: { Image(systemName: "arrow.clockwise") }
                    }
                    Button { showingPicker = true } label: { Image(systemName: "doc.badge.plus") }
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
        guard let server = auth.serverURL, let bearer = auth.bearer else {
            showingSettings = true
            return
        }
        Task { await model.loadPlan(from: PlanNetworkSource(baseURL: server, bearer: bearer)) }
    }
}

private struct EmptyState: View {
    let signedIn: Bool
    let onLoadPlan: () -> Void
    let onImport: () -> Void
    let onConnect: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label("No workout yet", systemImage: "figure.run")
        } description: {
            Text(signedIn
                 ? "Load your training plan from Iron Trainer, or open a .itw file."
                 : "Connect to Iron Trainer to sync your plan, or open a .itw file (Files, Mail, AirDrop).")
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
