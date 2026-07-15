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

    /// Run the weekly check-in as a background JOB and poll it home. Holding
    /// one request open for the whole ~30s LLM replan was fragile on a phone —
    /// backgrounding the app or a cellular blip killed the connection while
    /// the server kept working. Submit + short polls survive both.
    func checkin(feel: CheckinFeel? = nil) async throws -> CheckinStory {
        var comps = URLComponents(url: baseURL.appending(path: "/api/plan/checkin"),
                                  resolvingAgainstBaseURL: false)!
        comps.queryItems = [URLQueryItem(name: "async", value: "1")]
        var req = authedRequest(comps.url!, bearer: bearer)
        req.httpMethod = "POST"
        if let feel, !feel.isEmpty {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONEncoder().encode(CheckinRequestBody(inputs: feel))
        }
        let (data, resp) = try await session.data(for: req)
        if let h = resp as? HTTPURLResponse, h.statusCode != 200 { throw NetworkError.http(h.statusCode) }
        let envelope = try JSONDecoder().decode(JobEnvelope.self, from: data)
        return try await pollCheckinJob(id: envelope.job.id)
    }

    /// Poll a check-in job to a terminal state (2s interval, 10min cap).
    /// Mirrors the web's tolerance: a few dropped polls are fine — one blip
    /// must not fail a job that's still running server-side. Definitive
    /// answers (401/403/404) fail fast. Cancellation-cooperative via
    /// Task.sleep, so leaving the screen stops the loop cleanly.
    func pollCheckinJob(id: Int) async throws -> CheckinStory {
        let deadline = Date.now.addingTimeInterval(10 * 60)
        var misses = 0
        while true {
            do {
                let url = baseURL.appending(path: "/api/jobs/\(id)")
                let (data, resp) = try await session.data(for: authedRequest(url, bearer: bearer))
                if let h = resp as? HTTPURLResponse, h.statusCode != 200 {
                    // Any 4xx except 429 is a definitive answer about THIS
                    // request (bad id, lost auth, validation) — fail fast.
                    // 5xx and 429 are proxy/overload noise → miss budget.
                    if (400...499).contains(h.statusCode) && h.statusCode != 429 {
                        throw NetworkError.http(h.statusCode)
                    }
                    throw TransientPollError()
                }
                misses = 0
                let job = try JSONDecoder().decode(CheckinJob.self, from: data)
                switch job.status {
                case "succeeded":
                    guard let story = job.result else { throw NetworkError.http(500) }
                    return story
                case "failed":
                    throw CheckinFailed(message: job.error ?? "check-in failed")
                default:
                    break  // queued / running — keep polling
                }
            } catch is TransientPollError {
                misses += 1
                if misses > 4 { throw NetworkError.http(503) }
            } catch let e as URLError {
                misses += 1  // offline blip / app was suspended mid-request
                if misses > 4 { throw e }
            }
            guard Date.now < deadline else {
                throw CheckinFailed(message: "Check-in timed out — pull to refresh later.")
            }
            try await Task.sleep(for: .seconds(2))
        }
    }

    /// Today's readiness call (go hard / go easy / rest) from the athlete's
    /// acute:chronic training load. Best-effort: the Today view renders fine
    /// without it, so any failure just means no banner.
    func readinessToday() async -> ReadinessToday? {
        let url = baseURL.appending(path: "/api/metrics/readiness/today")
        guard let (data, resp) = try? await session.data(for: authedRequest(url, bearer: bearer)),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let readiness = try? JSONDecoder().decode(ReadinessToday.self, from: data)
        else { return nil }
        return readiness
    }

    /// The id of a check-in job that's already running for this athlete
    /// (started on the web, or before the app was killed) — lets the Today
    /// view re-attach instead of showing nothing.
    func activeCheckinJobID() async -> Int? {
        let url = baseURL.appending(path: "/api/jobs/summary")
        guard let (data, resp) = try? await session.data(for: authedRequest(url, bearer: bearer)),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let summary = try? JSONDecoder().decode(JobsSummary.self, from: data)
        else { return nil }
        return summary.active["checkin"]?.id
    }
}

/// Subjective check-in inputs — 1-5, higher is better, everything optional.
struct CheckinFeel: Encodable, Equatable {
    var energy: Int?
    var sleep: Int?
    var body: Int?
    var stress: Int?
    var note: String?

    var isEmpty: Bool {
        energy == nil && sleep == nil && body == nil && stress == nil
            && (note?.isEmpty ?? true)
    }
}

private struct CheckinRequestBody: Encodable {
    let inputs: CheckinFeel
}

/// Today's readiness call (subset of /api/metrics/readiness/today).
struct ReadinessToday: Decodable, Equatable {
    let status: String
    let call: String?
    let level: String?
    let reasons: [String]
}

/// The narrated result of a weekly check-in (subset of the API payload).
struct CheckinStory: Decodable {
    let status: String?
    let story: [String]
}

// ── Job-API payloads (subset of fields the app needs) ────────────────────────

struct JobEnvelope: Decodable {
    let job: JobStatus
}

struct JobStatus: Decodable {
    let id: Int
    let status: String
}

/// A polled check-in job: `result` is the full check-in payload, which
/// CheckinStory decodes (unknown keys ignored).
struct CheckinJob: Decodable {
    let status: String
    let error: String?
    let result: CheckinStory?
}

struct JobsSummary: Decodable {
    let active: [String: JobStatus]
}

struct CheckinFailed: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

private struct TransientPollError: Error {}
