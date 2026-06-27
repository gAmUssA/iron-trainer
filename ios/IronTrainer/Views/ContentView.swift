import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject private var model: ImportModel
    @State private var showingPicker = false

    var body: some View {
        NavigationStack {
            Group {
                switch model.state {
                case .empty:
                    EmptyState(showingPicker: $showingPicker)
                case .loading:
                    ProgressView("Reading workout…")
                case let .loaded(workout):
                    WorkoutPreviewView(workout: workout)
                case let .scheduled(message):
                    ResultState(systemImage: "checkmark.circle.fill",
                                tint: .green, message: message) { model.reset() }
                case let .failed(message):
                    ResultState(systemImage: "exclamationmark.triangle.fill",
                                tint: .orange, message: message) { model.reset() }
                }
            }
            .navigationTitle("Iron Trainer")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
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
        }
    }
}

private struct EmptyState: View {
    @Binding var showingPicker: Bool
    var body: some View {
        ContentUnavailableView {
            Label("No workout yet", systemImage: "figure.run")
        } description: {
            Text("Open a .itw file from Iron Trainer (Files, Mail, or AirDrop), or import one here.")
        } actions: {
            Button("Import .itw file") { showingPicker = true }
                .buttonStyle(.borderedProminent)
        }
    }
}

private struct ResultState: View {
    let systemImage: String
    let tint: Color
    let message: String
    let onDone: () -> Void
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: systemImage).font(.system(size: 48)).foregroundStyle(tint)
            Text(message).multilineTextAlignment(.center).padding(.horizontal)
            Button("Done", action: onDone).buttonStyle(.bordered)
        }
        .padding()
    }
}

/// Exported UTI for the .itw document type (also declared in Info.plist).
extension UTType {
    static let itw = UTType(exportedAs: "io.gamov.irontrainer.itw")
}
