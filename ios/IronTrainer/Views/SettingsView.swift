import SwiftUI

/// Connect the app to an Iron Trainer server: scan the web QR, or enter the
/// server URL + pairing code by hand. Stores a bearer token in the Keychain.
struct SettingsView: View {
    @EnvironmentObject private var auth: AuthModel
    @EnvironmentObject private var model: ImportModel
    @Environment(\.dismiss) private var dismiss
    @AppStorage(DistanceUnit.storageKey) private var unit: DistanceUnit = .km
    @AppStorage("checkinReminderEnabled") private var checkinReminder = false
    @AppStorage(Notifications.briefEnabledKey) private var morningBrief = false
    @State private var ingestToken: IngestToken?
    @State private var ingestBusy = false
    @State private var ingestError: String?
    @State private var lastRecovery: RecoveryDay?

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

                Section {
                    Toggle("Weekly check-in reminder", isOn: $checkinReminder)
                        .onChange(of: checkinReminder) { _, on in
                            Task { @MainActor in
                                if on {
                                    let ok = await Notifications.scheduleCheckinReminder()
                                    if !ok { checkinReminder = false }  // permission denied
                                } else {
                                    Notifications.cancelCheckinReminder()
                                }
                            }
                        }
                    Toggle("Morning brief", isOn: $morningBrief)
                        .onChange(of: morningBrief) { _, on in
                            Task { @MainActor in
                                if on {
                                    guard await Notifications.ensureAuthorized() else {
                                        morningBrief = false
                                        return
                                    }
                                    if let plan = model.lastPlan {
                                        await Notifications.rescheduleMorningBriefs(from: plan)
                                    }
                                } else {
                                    await Notifications.cancelMorningBriefs()
                                }
                            }
                        }
                } header: {
                    Text("Notifications")
                } footer: {
                    Text("Local nudges only — check-in reminder Mondays at 8:00; morning brief daily at 6:45 with today's session, fuel, and the race countdown. Nothing runs in the background.")
                }

                Section {
                    Picker("Distance units", selection: $unit) {
                        ForEach(DistanceUnit.allCases) { u in Text(u.label).tag(u) }
                    }
                    .pickerStyle(.segmented)
                } header: {
                    Text("Units")
                } footer: {
                    Text("Applies to distances and run/bike paces when browsing the plan. Swim paces stay per 100 m.")
                }

                Section {
                    if let t = ingestToken {
                        Text("Token created — shown once, copy it now.")
                            .font(.footnote)
                        Button("Copy Authorization header") {
                            UIPasteboard.general.string = t.header
                        }
                        if let server = auth.serverURL {
                            Button("Copy endpoint URL") {
                                UIPasteboard.general.string =
                                    server.appending(path: t.path).absoluteString
                            }
                        }
                    } else {
                        Button(ingestBusy ? "Working…" : "Create ingest token") {
                            mintIngestToken()
                        }
                        .disabled(ingestBusy || !auth.isSignedIn)
                    }
                    if let r = lastRecovery {
                        Text(recoveryLine(r)).font(.footnote).foregroundStyle(.secondary)
                    } else {
                        Text("No health data received yet.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    if let ingestError {
                        Text(ingestError).foregroundStyle(.red).font(.footnote)
                    }
                } header: {
                    Text("Health data (Apple Health)")
                } footer: {
                    Text("Push sleep, HRV and resting heart rate from the Health Auto Export app: Automations → REST API, paste the URL and header, select the three metrics, JSON, aggregate by days, Summarize ON. Feeds your readiness call.")
                }

                if let error {
                    Section { Text(error).foregroundStyle(.red).font(.footnote) }
                }
            }
            .navigationTitle("Settings")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
            .task { await loadRecoveryStatus() }
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

    private func mintIngestToken() {
        guard let server = auth.serverURL, let bearer = auth.bearer else { return }
        ingestBusy = true
        ingestError = nil
        Task { @MainActor in
            defer { ingestBusy = false }
            do {
                ingestToken = try await PlanNetworkSource(baseURL: server, bearer: bearer)
                    .mintIngestToken()
            } catch {
                ingestError = error.localizedDescription
            }
        }
    }

    @MainActor
    private func loadRecoveryStatus() async {
        guard let server = auth.serverURL, let bearer = auth.bearer else { return }
        lastRecovery = await PlanNetworkSource(baseURL: server, bearer: bearer).latestRecovery()
    }

    private func recoveryLine(_ r: RecoveryDay) -> String {
        var bits: [String] = []
        if let s = r.sleep_h { bits.append(String(format: "sleep %.1fh", s)) }
        if let h = r.hrv_ms { bits.append(String(format: "HRV %.0fms", h)) }
        if let b = r.rhr_bpm { bits.append(String(format: "RHR %.0f", b)) }
        let detail = bits.isEmpty ? "received" : bits.joined(separator: " · ")
        return "Last push: \(r.date) — \(detail)"
    }

}
