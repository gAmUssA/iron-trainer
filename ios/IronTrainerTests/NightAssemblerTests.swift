import XCTest
import HealthKit
@testable import IronTrainer

/// Unit tests for the pure sleep/gauge assembler (iron-trainer-90iu). A UTC
/// calendar makes the prev-15:00→15:00 night windowing deterministic.
final class NightAssemblerTests: XCTestCase {
    private var utc: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }()

    private func date(_ y: Int, _ mo: Int, _ d: Int, _ h: Int, _ mi: Int = 0) -> Date {
        DateComponents(calendar: utc, timeZone: utc.timeZone,
                       year: y, month: mo, day: d, hour: h, minute: mi).date!
    }

    private func sleep(_ stage: HKCategoryValueSleepAnalysis, _ start: Date, _ end: Date,
                       _ src: String = "watch") -> SleepSample {
        SleepSample(stage: stage, start: start, end: end, sourceBundleID: src, uuid: UUID())
    }

    private func qty(_ m: QuantityMetric, _ v: Double, _ start: Date,
                     _ src: String = "watch") -> QuantitySample {
        QuantitySample(metric: m, value: v, start: start, end: start, sourceBundleID: src, uuid: UUID())
    }

    // MARK: night bucketing (the +9h boundary)

    func testNightDateBoundary() {
        // 15:00 belongs to the NEXT night; 14:59 to the current one.
        XCTAssertEqual(NightAssembler.nightDate(for: date(2026, 7, 20, 22, 0), utc), date(2026, 7, 21, 0))
        XCTAssertEqual(NightAssembler.nightDate(for: date(2026, 7, 21, 2, 0), utc), date(2026, 7, 21, 0))
        XCTAssertEqual(NightAssembler.nightDate(for: date(2026, 7, 21, 14, 59), utc), date(2026, 7, 21, 0))
        XCTAssertEqual(NightAssembler.nightDate(for: date(2026, 7, 21, 15, 0), utc), date(2026, 7, 22, 0))
    }

    // MARK: interval union (Garmin re-sync overlap)

    func testUnionSecondsDeduplicatesOverlap() {
        let a = (date(2026, 7, 20, 22, 0), date(2026, 7, 20, 23, 0))   // 1h
        let b = (date(2026, 7, 20, 22, 30), date(2026, 7, 20, 23, 30)) // overlaps → union 22:00–23:30
        XCTAssertEqual(NightAssembler.unionSeconds([a, b]), 1.5 * 3600, accuracy: 0.1)
        // disjoint intervals just sum
        let c = (date(2026, 7, 21, 1, 0), date(2026, 7, 21, 1, 30))    // 0.5h
        XCTAssertEqual(NightAssembler.unionSeconds([a, c]), 1.5 * 3600, accuracy: 0.1)
    }

    // MARK: stage sums + total + awake excluded

    func testSingleSourceNightStageSums() {
        let s = [
            sleep(.asleepDeep, date(2026, 7, 20, 22, 0), date(2026, 7, 20, 23, 0)),  // 1h
            sleep(.asleepCore, date(2026, 7, 20, 23, 0), date(2026, 7, 21, 1, 0)),   // 2h
            sleep(.asleepREM,  date(2026, 7, 21, 1, 0), date(2026, 7, 21, 2, 0)),    // 1h
            sleep(.awake,      date(2026, 7, 21, 2, 0), date(2026, 7, 21, 2, 15)),   // 0.25h
        ]
        let out = NightAssembler.assemble(sleep: s, quantities: [], calendar: utc)
        XCTAssertEqual(out.count, 1)
        let n = out[0]
        XCTAssertEqual(n.day, date(2026, 7, 21, 0))
        XCTAssertEqual(n.deepH!, 1, accuracy: 0.01)
        XCTAssertEqual(n.coreH!, 2, accuracy: 0.01)
        XCTAssertEqual(n.remH!, 1, accuracy: 0.01)
        XCTAssertEqual(n.awakeH!, 0.25, accuracy: 0.01)
        XCTAssertEqual(n.sleepH!, 4, accuracy: 0.01)   // deep+core+rem, awake NOT counted
    }

    // MARK: per-source winner (longest asleep wins; never merge sources)

    func testWinningSourcePickedByLongestAsleep() {
        let s = [
            // iPhone: only 0.5h "asleep" (an inBed estimate + a short stint)
            sleep(.asleepUnspecified, date(2026, 7, 20, 22, 0), date(2026, 7, 20, 22, 30), "iphone"),
            // Watch: 3h of staged sleep → should win
            sleep(.asleepDeep, date(2026, 7, 20, 22, 0), date(2026, 7, 20, 23, 0), "watch"),
            sleep(.asleepCore, date(2026, 7, 20, 23, 0), date(2026, 7, 21, 1, 0), "watch"),
        ]
        let out = NightAssembler.assemble(sleep: s, quantities: [], calendar: utc)
        XCTAssertEqual(out.count, 1)
        // Watch won → deep 1h + core 2h = 3h total; the iPhone 0.5h is not merged in.
        XCTAssertEqual(out[0].sleepH!, 3, accuracy: 0.01)
        XCTAssertNil(out[0].unspecifiedH)   // iphone's stage was dropped, not merged
    }

    // MARK: overnight gauges use the sleep window; daytime samples excluded

    func testHrvUsesSleepWindowMean() {
        let s = [sleep(.asleepCore, date(2026, 7, 20, 23, 0), date(2026, 7, 21, 5, 0))]
        let q = [
            qty(.hrv, 60, date(2026, 7, 20, 23, 30)),  // in window
            qty(.hrv, 40, date(2026, 7, 21, 4, 0)),    // in window → mean 50
            qty(.hrv, 999, date(2026, 7, 21, 12, 0)),  // daytime → excluded
        ]
        let out = NightAssembler.assemble(sleep: s, quantities: q, calendar: utc)
        XCTAssertEqual(out[0].hrvMs!, 50, accuracy: 0.01)
    }

    // MARK: calendar-day gauges

    func testDailyGaugesRhrMeanBodyMassLatest() {
        let day = date(2026, 7, 21, 0)
        let q = [
            qty(.restingHeartRate, 50, date(2026, 7, 21, 6, 0)),
            qty(.restingHeartRate, 54, date(2026, 7, 21, 20, 0)),   // mean 52
            qty(.bodyMass, 80, date(2026, 7, 21, 7, 0)),
            qty(.bodyMass, 79.5, date(2026, 7, 21, 21, 0)),         // latest 79.5
        ]
        let out = NightAssembler.assemble(sleep: [], quantities: q, calendar: utc)
        let rec = out.first { $0.day == day }!
        XCTAssertEqual(rec.rhrBpm!, 52, accuracy: 0.01)
        XCTAssertEqual(rec.bodyMassKg!, 79.5, accuracy: 0.01)
    }

    // MARK: multi-night bucketing (cross-night bleed)

    func testMultipleNightsBucketedSeparately() {
        let s = [
            sleep(.asleepCore, date(2026, 7, 20, 23, 0), date(2026, 7, 21, 5, 0)),  // night 21
            sleep(.asleepCore, date(2026, 7, 21, 23, 0), date(2026, 7, 22, 5, 0)),  // night 22
        ]
        let out = NightAssembler.assemble(sleep: s, quantities: [], calendar: utc)
        XCTAssertEqual(out.map(\.day), [date(2026, 7, 21, 0), date(2026, 7, 22, 0)])  // sorted
        XCTAssertEqual(out[0].coreH!, 6, accuracy: 0.01)
        XCTAssertEqual(out[1].coreH!, 6, accuracy: 0.01)
    }

    // MARK: winner tie resolves deterministically (no per-launch flapping)

    func testWinnerTieIsDeterministic() {
        // two sources, equal 2h asleep, different stages → (asleep,src) max picks "zzz".
        let s = [
            sleep(.asleepDeep, date(2026, 7, 20, 23, 0), date(2026, 7, 21, 1, 0), "aaa"),
            sleep(.asleepREM,  date(2026, 7, 20, 23, 0), date(2026, 7, 21, 1, 0), "zzz"),
        ]
        for _ in 0..<5 {
            let out = NightAssembler.assemble(sleep: s, quantities: [], calendar: utc)
            XCTAssertEqual(out.count, 1)
            XCTAssertEqual(out[0].remH!, 2, accuracy: 0.01)   // "zzz" won
            XCTAssertNil(out[0].deepH)                        // "aaa" dropped, not merged
        }
    }

    func testEmptyInput() {
        XCTAssertTrue(NightAssembler.assemble(sleep: [], quantities: [], calendar: utc).isEmpty)
    }

    // MARK: overnight gauge keeps a straddling interval + single dominant source

    func testWindowGaugeStraddleAndSingleSource() {
        let s = [sleep(.asleepCore, date(2026, 7, 20, 23, 0), date(2026, 7, 21, 5, 0))]  // window 23:00–05:00
        let q = [
            // respiratory interval starts 22:59 (before window) but overlaps → kept
            QuantitySample(metric: .respiratoryRate, value: 14,
                           start: date(2026, 7, 20, 22, 59), end: date(2026, 7, 20, 23, 10),
                           sourceBundleID: "watch", uuid: UUID()),
            // HRV: "watch" (2 in-window) dominates "thirdparty" (1) → watch-only mean
            qty(.hrv, 60, date(2026, 7, 21, 0, 0), "watch"),
            qty(.hrv, 40, date(2026, 7, 21, 2, 0), "watch"),
            qty(.hrv, 999, date(2026, 7, 21, 1, 0), "thirdparty"),
        ]
        let out = NightAssembler.assemble(sleep: s, quantities: q, calendar: utc)
        XCTAssertEqual(out[0].respiratoryRate!, 14, accuracy: 0.01)   // straddling sample kept
        XCTAssertEqual(out[0].hrvMs!, 50, accuracy: 0.01)             // watch mean, thirdparty excluded
    }
}
