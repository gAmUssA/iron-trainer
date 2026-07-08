import Foundation

/// Where a `.itw` workout comes from. The MVP ships `FileImportSource`; a
/// `NetworkSource` that calls the backend `/api/export/...` directly can be
/// added later without touching the rest of the app (the "Both" decision).
protocol WorkoutSource {
    func load() async throws -> ItwWorkout
}

/// Reads a `.itw` file the user opened from Files / Mail / AirDrop, or that the
/// system handed us via `onOpenURL`.
struct FileImportSource: WorkoutSource {
    let url: URL

    func load() async throws -> ItwWorkout {
        // Security-scoped access is needed for files outside our sandbox.
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data = try Data(contentsOf: url)
        return try ItwWorkout.decode(from: data)
    }
}

enum NetworkError: LocalizedError {
    case notSignedIn
    case http(Int)

    var errorDescription: String? {
        switch self {
        case .notSignedIn: return "Sign in to Iron Trainer first (Settings → Connect)."
        case let .http(code): return "Server returned HTTP \(code)."
        }
    }
}

private func authedRequest(_ url: URL, bearer: String) -> URLRequest {
    var req = URLRequest(url: url)
    req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
    req.setValue("application/json", forHTTPHeaderField: "Accept")
    return req
}

/// Fetch a single workout: GET /api/export/workout/{id}.itw (bearer-authenticated).
struct NetworkSource: WorkoutSource {
    let baseURL: URL
    let bearer: String
    let workoutID: Int
    var session: URLSession = .shared

    func load() async throws -> ItwWorkout {
        let url = baseURL.appending(path: "/api/export/workout/\(workoutID).itw")
        let (data, resp) = try await session.data(for: authedRequest(url, bearer: bearer))
        if let h = resp as? HTTPURLResponse, h.statusCode != 200 { throw NetworkError.http(h.statusCode) }
        return try ItwWorkout.decode(from: data)
    }
}

/// Fetch the whole plan: GET /api/export/plan.itw (bearer-authenticated).
struct PlanNetworkSource {
    let baseURL: URL
    let bearer: String
    var session: URLSession = .shared

    /// Returns workouts AND the plan meta (race name/date) — the Today view and
    /// widgets need the meta the old API silently discarded.
    func loadPlan() async throws -> TrainingPlan {
        let url = baseURL.appending(path: "/api/export/plan.itw")
        let (data, resp) = try await session.data(for: authedRequest(url, bearer: bearer))
        if let h = resp as? HTTPURLResponse, h.statusCode != 200 { throw NetworkError.http(h.statusCode) }
        let file = try PlanFile.decode(from: data)
        return TrainingPlan(meta: file.plan, workouts: file.workouts)
    }
}
