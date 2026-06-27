import Foundation
import UIKit

/// App-wide auth state: the server base URL (UserDefaults) and the bearer token
/// (Keychain). Pairing exchanges a code for a token via POST /api/device/claim.
@MainActor
final class AuthModel: ObservableObject {
    @Published private(set) var serverURL: URL?
    @Published private(set) var isSignedIn: Bool
    @Published private(set) var athleteName: String?

    private let tokenAccount = "bearer"
    private let serverKey = "iron.serverURL"
    private let nameKey = "iron.athleteName"

    init() {
        let s = UserDefaults.standard.string(forKey: serverKey)
        serverURL = s.flatMap(URL.init(string:))
        athleteName = UserDefaults.standard.string(forKey: nameKey)
        isSignedIn = Keychain.get("bearer") != nil
    }

    var bearer: String? { Keychain.get(tokenAccount) }

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
        Keychain.set(claim.token, account: tokenAccount)
        UserDefaults.standard.set(server.absoluteString, forKey: serverKey)
        let name = claim.athlete?.name
        UserDefaults.standard.set(name, forKey: nameKey)
        serverURL = server
        athleteName = name
        isSignedIn = true
    }

    func signOut() {
        Keychain.delete(tokenAccount)
        UserDefaults.standard.removeObject(forKey: serverKey)
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
