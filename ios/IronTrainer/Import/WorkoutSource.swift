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

/// Deferred: fetch directly from the backend once in-app auth exists.
/// Endpoint shape is already designed: GET /api/export/workout/{id}.itw
struct NetworkSource: WorkoutSource {
    let baseURL: URL
    let workoutID: Int
    var session: URLSession = .shared
    // TODO: inject auth (cookie/session) when in-app login lands.

    func load() async throws -> ItwWorkout {
        let url = baseURL.appending(path: "/api/export/workout/\(workoutID).itw")
        let (data, _) = try await session.data(from: url)
        return try ItwWorkout.decode(from: data)
    }
}
