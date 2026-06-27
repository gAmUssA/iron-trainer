import SwiftUI

@main
struct IronTrainerApp: App {
    @StateObject private var model = ImportModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                // Files / Mail / AirDrop / web download open the app here.
                .onOpenURL { url in
                    Task { await model.importFrom(FileImportSource(url: url)) }
                }
        }
    }
}
