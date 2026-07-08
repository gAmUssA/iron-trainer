import SwiftUI

/// Connect the app to an Iron Trainer server: scan the web QR, or enter the
/// server URL + pairing code by hand. Stores a bearer token in the Keychain.
struct SettingsView: View {
    @EnvironmentObject private var auth: AuthModel
    @Environment(\.dismiss) private var dismiss

    @State private var serverText = ""
    @State private var code = ""
    @State private var scanning = false
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                if auth.isSignedIn {
                    Section("Connected") {
                        LabeledContent("Server", value: auth.serverURL?.host() ?? "—")
                        if let n = auth.athleteName { LabeledContent("Athlete", value: n) }
                        // Widget-data diagnostic: pinpoints whether a blank widget
                        // means "app never wrote the snapshot" or a widget-side issue.
                        LabeledContent("Widget data", value: widgetDataStatus)
                        Button("Sign out", role: .destructive) { auth.signOut() }
                    }
                } else {
                    Section {
                        if QRScannerView.isSupported {
                            Button {
                                error = nil
                                scanning = true
                            } label: {
                                Label("Scan QR from the web app", systemImage: "qrcode.viewfinder")
                            }
                        }
                    } header: {
                        Text("Connect")
                    } footer: {
                        Text("In the web app: Setup → Connect iOS app → scan the QR. Or enter the details below.")
                    }

                    Section("Or enter manually") {
                        TextField("Server URL (https://…)", text: $serverText)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                        TextField("Pairing code", text: $code)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        Button(busy ? "Connecting…" : "Connect") {
                            Task { await connect(server: serverText, code: code) }
                        }
                        .disabled(busy || serverText.isEmpty || code.isEmpty)
                    }
                }

                if let error {
                    Section { Text(error).foregroundStyle(.red).font(.footnote) }
                }
            }
            .navigationTitle("Settings")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
            .sheet(isPresented: $scanning) {
                NavigationStack {
                    QRScannerView { payload in
                        scanning = false
                        guard let (url, scanned) = parsePairingPayload(payload) else {
                            error = "That QR isn’t an Iron Trainer pairing code."
                            return
                        }
                        Task { await connect(server: url.absoluteString, code: scanned) }
                    }
                    .ignoresSafeArea()
                    .navigationTitle("Scan QR")
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("Cancel") { scanning = false }
                        }
                    }
                }
            }
        }
    }

    private var widgetDataStatus: String {
        guard let snapshot = SharedStore.read() else { return "not written yet" }
        return "updated " + snapshot.generatedAt.formatted(.relative(presentation: .named))
    }

    private func connect(server: String, code: String) async {
        guard let url = URL(string: server.trimmingCharacters(in: .whitespaces)) else {
            error = "Invalid server URL."
            return
        }
        busy = true
        error = nil
        do {
            try await auth.signIn(server: url, code: code)
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
        busy = false
    }
}
