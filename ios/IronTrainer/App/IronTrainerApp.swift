import SwiftUI

/// A pairing request awaiting user confirmation (deep link while already signed in).
struct PendingPairing: Identifiable {
    let server: URL
    let code: String
    var id: String { server.absoluteString + code }
}

@main
struct IronTrainerApp: App {
    @StateObject private var model = ImportModel()
    @StateObject private var auth = AuthModel()
    @State private var pendingPairing: PendingPairing?
    @State private var pairingError: String?

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .environmentObject(auth)
                // A pairing link (irontrainer://pair?…) signs in and loads the plan;
                // anything else is a .itw file from Files / Mail / AirDrop.
                .onOpenURL { url in
                    if let (server, code) = parsePairingPayload(url.absoluteString) {
                        // Already paired to a different server? A stray/malicious
                        // link must not silently replace the session — confirm.
                        if auth.isSignedIn, auth.serverURL != server {
                            pendingPairing = PendingPairing(server: server, code: code)
                        } else {
                            Task { await pair(server: server, code: code) }
                        }
                    } else {
                        Task { await model.importFrom(FileImportSource(url: url)) }
                    }
                }
                .alert(item: $pendingPairing) { pending in
                    Alert(
                        title: Text("Switch server?"),
                        message: Text("This link pairs the app with \(pending.server.absoluteString), replacing your current sign-in."),
                        primaryButton: .destructive(Text("Switch")) {
                            Task { await pair(server: pending.server, code: pending.code) }
                        },
                        secondaryButton: .cancel()
                    )
                }
                .alert("Pairing failed", isPresented: .init(
                    get: { pairingError != nil },
                    set: { if !$0 { pairingError = nil } }
                )) {
                    Button("OK", role: .cancel) {}
                } message: {
                    Text(pairingError ?? "")
                }
        }
    }

    @MainActor
    private func pair(server: URL, code: String) async {
        do {
            try await auth.signIn(server: server, code: code)
        } catch {
            // Failed pairing must not fall through to a plan refresh against the
            // previously stored server/bearer — and must not fail silently.
            pairingError = error.localizedDescription
            return
        }
        if let s = auth.serverURL, let b = auth.bearer {
            await model.loadPlan(from: PlanNetworkSource(baseURL: s, bearer: b))
        }
    }
}
