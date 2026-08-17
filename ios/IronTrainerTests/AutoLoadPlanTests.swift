import XCTest
@testable import IronTrainer

/// Stubs the plan fetch so the launch auto-load can be exercised without a server.
private final class StubURLProtocol: URLProtocol {
    /// Set per test; returns the response for any request, or throws to simulate
    /// being offline.
    static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        guard let handler = Self.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
            return
        }
        do {
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }
}

/// The auto-load runs without anyone asking for it, which imposes two rules the
/// manual load doesn't have: it must never replace what the user is looking at,
/// and it must never dead-end them in a full-screen error. Both are invisible
/// when broken — you'd only notice by losing your place — so they're pinned here.
@MainActor
final class AutoLoadPlanTests: XCTestCase {

    private var source: PlanNetworkSource!

    override func setUp() {
        super.setUp()
        PlanCache.clear()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        source = PlanNetworkSource(baseURL: URL(string: "https://example.test")!,
                                   bearer: "t",
                                   session: URLSession(configuration: config))
    }

    override func tearDown() {
        StubURLProtocol.handler = nil
        PlanCache.clear()
        super.tearDown()
    }

    // MARK: fixtures

    private func planJSON(_ titles: [String]) -> Data {
        let workouts = titles.map {
            """
            {"schema_version":1,"date":"2026-08-17","sport":"Bike","title":"\($0)","steps":[]}
            """
        }.joined(separator: ",")
        return Data("""
        {"schema_version":1,"plan":{"race_name":"IM 70.3 NY","race_date":"2026-09-26"},
         "workouts":[\(workouts)]}
        """.utf8)
    }

    private func serve(_ data: Data, status: Int = 200) {
        StubURLProtocol.handler = { req in
            (HTTPURLResponse(url: req.url!, statusCode: status,
                             httpVersion: nil, headerFields: nil)!, data)
        }
    }

    private func serveOffline() {
        StubURLProtocol.handler = { _ in throw URLError(.notConnectedToInternet) }
    }

    private func itw(_ title: String) -> ItwWorkout {
        ItwWorkout(schemaVersion: 1, generator: nil, date: "2026-08-17", sport: "Bike",
                   title: title, description: nil, durationS: 3600, distanceM: nil,
                   athlete: nil, steps: [])
    }

    // MARK: the happy path

    func testAutoLoadPutsThePlanOnScreenWithNoUserAction() async {
        serve(planJSON(["Long ride"]))
        let model = ImportModel()
        XCTAssertEqual(model.state, .empty)

        await model.autoLoadPlan(from: source)

        guard case let .loadedPlan(p) = model.state else {
            return XCTFail("expected .loadedPlan, got \(model.state)")
        }
        XCTAssertEqual(p.workouts.first?.title, "Long ride")
        XCTAssertNotNil(PlanCache.read(), "a successful auto-load must seed the offline cache")
    }

    // MARK: failure must not dead-end

    func testOfflineWithNothingCachedFallsBackToEmptyWithAReasonNotAnError() async {
        serveOffline()
        let model = ImportModel()

        await model.autoLoadPlan(from: source)

        // .failed would strand the user on an error screen after an action they
        // never took — the import button and manual retry must stay reachable.
        XCTAssertEqual(model.state, .empty)
        XCTAssertNotNil(model.autoLoadNotice, "the empty state has to explain itself")
    }

    func testOfflineKeepsTheCachedPlanAndStaysSilent() async {
        PlanCache.write(TrainingPlan(meta: nil, workouts: [itw("Cached ride")]))
        let model = ImportModel()          // restores from cache at init
        serveOffline()

        await model.autoLoadPlan(from: source)

        guard case let .loadedPlan(p) = model.state else {
            return XCTFail("a failed background refresh must not discard the cached plan")
        }
        XCTAssertEqual(p.workouts.first?.title, "Cached ride")
        XCTAssertNil(model.autoLoadNotice, "nothing to report — the user still has a plan")
    }

    // MARK: must not hijack the user's context

    func testAutoLoadAlreadyInFlightDoesNotClobberAWorkoutOpenedMeanwhile() async {
        // THE actual race: a .itw arrives via onOpenURL *while* the launch fetch
        // is outstanding. The pre-flight guard cannot help — it already passed —
        // so this only holds if the guard is re-checked after the response lands.
        // Whoever the user is looking at wins; being yanked into the plan
        // mid-preview is worse than a slow load.
        let release = XCTestExpectation(description: "response released")
        let inFlight = XCTestExpectation(description: "request started")
        let body = planJSON(["Long ride"])
        StubURLProtocol.handler = { req in
            inFlight.fulfill()
            // Block the URLProtocol worker thread until the test says go.
            _ = XCTWaiter().wait(for: [release], timeout: 5)
            return (HTTPURLResponse(url: req.url!, statusCode: 200,
                                    httpVersion: nil, headerFields: nil)!, body)
        }

        let model = ImportModel()
        async let auto: Void = model.autoLoadPlan(from: source)
        await fulfillment(of: [inFlight], timeout: 5)

        // The user opens a file while the plan request is still outstanding.
        await model.importFrom(StubWorkoutSource(workout: itw("Opened file")))
        guard case .loaded = model.state else { return XCTFail("setup: expected .loaded") }

        release.fulfill()
        await auto

        guard case let .loaded(w) = model.state else {
            return XCTFail("the in-flight auto-load clobbered the previewed workout: \(model.state)")
        }
        XCTAssertEqual(w.title, "Opened file")
    }

    func testAutoLoadSkipsEntirelyWhenAWorkoutIsAlreadyOnScreen() {
        // The cheaper pre-flight guard, kept separate so a regression in either
        // one is unambiguous.
        serve(planJSON(["Long ride"]))
        let model = ImportModel()
        let exp = expectation(description: "done")
        Task {
            await model.importFrom(StubWorkoutSource(workout: self.itw("Opened file")))
            await model.autoLoadPlan(from: self.source)
            exp.fulfill()
        }
        wait(for: [exp], timeout: 5)
        guard case let .loaded(w) = model.state else {
            return XCTFail("auto-load clobbered the previewed workout: \(model.state)")
        }
        XCTAssertEqual(w.title, "Opened file")
    }

    // MARK: signed in, but no plan generated yet

    func testEmptyPlanFromServerExplainsItselfAndClearsStaleCache() async {
        PlanCache.write(TrainingPlan(meta: nil, workouts: [itw("Old ride")]))
        serve(planJSON([]))
        let model = ImportModel()

        await model.autoLoadPlan(from: source)

        XCTAssertEqual(model.state, .empty)
        XCTAssertEqual(model.autoLoadNotice,
                       "No plan yet — generate one in the web app, then refresh.")
        XCTAssertNil(PlanCache.read(),
                     "the server says there's no plan — keeping the old one would show a lie")
    }
}

/// Minimal WorkoutSource so a test can put the model into `.loaded` without a file.
private struct StubWorkoutSource: WorkoutSource {
    let workout: ItwWorkout
    func load() async throws -> ItwWorkout { workout }
}
