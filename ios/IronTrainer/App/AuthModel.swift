import Foundation
import UIKit

/// App-wide auth state: the server base URL (UserDefaults) and the bearer token
/// (Keychain). Pairing exchanges a code for a token via POST /api/device/claim.
@MainActor
final class AuthModel: ObservableObject {
    @Published private(set) var serverURL: URL?
    @Published private(set) var isSignedIn: Bool
    @Published private(set) var athleteName: String?

    // Shared with NativeHealthSync so a background sync reads the same locations
    // (single source of truth for the storage keys).
    static let tokenAccount = "bearer"
    static let serverKey = "iron.serverURL"
    private let nameKey = "iron.athleteName"

    init() {
        let s = UserDefaults.standard.string(forKey: Self.serverKey)
        serverURL = s.flatMap(URL.init(string:))
        athleteName = UserDefaults.standard.string(forKey: nameKey)
        isSignedIn = Keychain.get(Self.tokenAccount) != nil
    }

    var bearer: String? { Keychain.get(Self.tokenAccount) }

    /// Exchange a pairing code (and server URL) for a bearer token.
    func signIn(server: URL, code: String) async throws {
        var req = URLRequest(url: server.appending(path: "/api/device/claim"))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: [
            "code": code.trimmingCharacters(in: .whitespaces),
            "device_name": UIDevice.current.name,
        ])
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NetworkError.http((resp as? HTTPURLResponse)?.statusCode ?? -1)
        }
        let claim = try JSONDecoder().decode(ClaimResponse.self, from: data)
        Keychain.set(claim.token, account: Self.tokenAccount)
        UserDefaults.standard.set(server.absoluteString, forKey: Self.serverKey)
        let name = claim.athlete?.name
        UserDefaults.standard.set(name, forKey: nameKey)
        serverURL = server
        athleteName = name
        isSignedIn = true
    }

    func signOut() {
        // Best-effort server-side revocation so the bearer token doesn't stay
        // valid forever; local state is cleared regardless of the outcome.
        if let server = serverURL, let token = bearer {
            var req = URLRequest(url: server.appending(path: "/api/device/tokens"))
            req.httpMethod = "DELETE"
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            Task { _ = try? await URLSession.shared.data(for: req) }
        }
        Keychain.delete(Self.tokenAccount)
        UserDefaults.standard.removeObject(forKey: Self.serverKey)
        UserDefaults.standard.removeObject(forKey: nameKey)
        serverURL = nil
        athleteName = nil
        isSignedIn = false
    }

    private struct ClaimResponse: Decodable {
        let token: String
        let athlete: Athlete?
        struct Athlete: Decodable { let name: String?; let strava_athlete_id: Int? }
    }
}
