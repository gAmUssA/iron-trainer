import SwiftUI

@main
struct IronTrainerApp: App {
    @StateObject private var model = ImportModel()
    @StateObject private var auth = AuthModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .environmentObject(auth)
                // A pairing link (irontrainer://pair?…) signs in and loads the plan;
                // anything else is a .itw file from Files / Mail / AirDrop.
                .onOpenURL { url in
                    if let (server, code) = parsePairingPayload(url.absoluteString) {
                        Task {
                            try? await auth.signIn(server: server, code: code)
                            if let s = auth.serverURL, let b = auth.bearer {
                                await model.loadPlan(from: PlanNetworkSource(baseURL: s, bearer: b))
                            }
                        }
                    } else {
                        Task { await model.importFrom(FileImportSource(url: url)) }
                    }
                }
        }
    }
}
