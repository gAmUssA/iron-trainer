import XCTest
@testable import IronTrainer

/// The launch auto-load (bean below) turns on two behaviours that are easy to
/// get subtly wrong and invisible when they break: the plan must survive a
/// process restart so Today renders offline, and an UNREQUESTED refresh must
/// never overwrite what the user is looking at.
final class PlanCacheTests: XCTestCase {

    override func setUp() {
        super.setUp()
        PlanCache.clear()
    }

    override func tearDown() {
        PlanCache.clear()
        super.tearDown()
    }

    private func workout(_ title: String) -> ItwWorkout {
        ItwWorkout(schemaVersion: 1, generator: "test", date: "2026-08-17", sport: "Bike",
                   title: title, description: nil, durationS: 3600, distanceM: nil,
                   athlete: nil, steps: [])
    }

    private func plan(_ titles: [String]) -> TrainingPlan {
        TrainingPlan(
            meta: PlanFile.PlanMeta(raceName: "IM 70.3 NY", raceDate: "2026-09-26", summary: nil),
            workouts: titles.map(workout)
        )
    }

    // MARK: round-trip

    func testCacheRoundTripsThePlan() {
        let original = plan(["Long ride", "Brick run"])
        XCTAssertTrue(PlanCache.write(original))
        let restored = PlanCache.read()
        XCTAssertEqual(restored, original,
                       "a cached plan must come back byte-identical — this is what Today renders offline")
    }

    func testEmptyCacheReadsNil() {
        XCTAssertNil(PlanCache.read())
    }

    func testClearRemovesIt() {
        PlanCache.write(plan(["Long ride"]))
        XCTAssertNotNil(PlanCache.read())
        PlanCache.clear()
        XCTAssertNil(PlanCache.read(), "sign-out must not leave the plan on disk for the next athlete")
    }

    func testCorruptCacheIsTreatedAsAbsentNotFatal() throws {
        PlanCache.write(plan(["Long ride"]))
        // Simulate a model change / partial write: the read must degrade to "no
        // cache" so the network load behind it repopulates, never trap.
        let dir = try FileManager.default.url(for: .applicationSupportDirectory,
                                              in: .userDomainMask,
                                              appropriateFor: nil, create: true)
        try Data("not json".utf8).write(to: dir.appendingPathComponent("plan.json"))
        XCTAssertNil(PlanCache.read())
    }

    // MARK: restore-at-init

    @MainActor
    func testInitRestoresCachedPlanSoTodayIsUpImmediately() {
        PlanCache.write(plan(["Long ride"]))
        let model = ImportModel()
        guard case let .loadedPlan(p) = model.state else {
            return XCTFail("expected .loadedPlan at init, got \(model.state)")
        }
        XCTAssertEqual(p.workouts.count, 1)
    }

    @MainActor
    func testInitStaysEmptyWithNoCache() {
        let model = ImportModel()
        XCTAssertEqual(model.state, .empty)
    }

    @MainActor
    func testInitIgnoresACachedPlanWithNoWorkouts() {
        // An empty plan is not something to show — it would render a blank Today
        // instead of the empty state that tells you to generate one.
        PlanCache.write(plan([]))
        XCTAssertEqual(ImportModel().state, .empty)
    }

    // MARK: forget-on-sign-out

    @MainActor
    func testForgetPlanClearsScreenAndDisk() {
        PlanCache.write(plan(["Long ride"]))
        let model = ImportModel()
        model.forgetPlan()
        XCTAssertEqual(model.state, .empty)
        XCTAssertNil(PlanCache.read())
        XCTAssertNil(model.lastPlan)
    }

    @MainActor
    func testForgetPlanAlsoClearsTheAppGroupWidgetSnapshot() {
        // The snapshot lives in the App Group and is readable with no auth, so
        // leaving it behind keeps the previous athlete's workouts on the home
        // screen after sign-out — the plan cache alone is not the whole story.
        SharedStore.write(WidgetSnapshot.build(from: plan(["Long ride"]), readiness: nil))
        XCTAssertNotNil(SharedStore.read(), "setup: snapshot should exist")

        ImportModel().forgetPlan()

        XCTAssertNil(SharedStore.read(),
                     "sign-out must not leave plan data in the shared container")
    }
}
