import XCTest
@testable import IronTrainer

/// Payload-shape tests for the native ingest client (iron-trainer-n1zt). Verifies
/// the HAE-compatible structure and that only non-nil fields are emitted (so a
/// partial sync can't overwrite fuller values under the backend's per-field upsert).
final class HealthIngestClientTests: XCTestCase {
    private func day(_ y: Int, _ m: Int, _ d: Int) -> Date {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = .current
        return c.date(from: DateComponents(year: y, month: m, day: d))!
    }

    private func metrics(_ payload: [String: Any]) -> [[String: Any]] {
        ((payload["data"] as? [String: Any])?["metrics"] as? [[String: Any]]) ?? []
    }

    func testPayloadMapsNamesAndOmitsNilFields() {
        var r = DailyRecovery(day: day(2026, 7, 21))
        r.hrvMs = 55
        r.rhrBpm = 48
        r.deepH = 1.2
        r.coreH = 4
        r.remH = 1.5
        // vo2Max / bodyMass / respiratoryRate / wristTempC left nil

        let ms = metrics(HealthIngestClient.payload([r]))
        let names = ms.compactMap { $0["name"] as? String }
        XCTAssertTrue(names.contains("heart_rate_variability"))
        XCTAssertTrue(names.contains("resting_heart_rate"))
        XCTAssertTrue(names.contains("sleep_analysis"))
        XCTAssertFalse(names.contains("vo2max"))          // nil → omitted
        XCTAssertFalse(names.contains("weight_body_mass"))
        XCTAssertFalse(names.contains("respiratory_rate"))

        let hrv = ms.first { $0["name"] as? String == "heart_rate_variability" }!
        XCTAssertEqual(hrv["units"] as? String, "ms")
        let point = (hrv["data"] as! [[String: Any]])[0]
        XCTAssertEqual(point["qty"] as? Double, 55)
        XCTAssertNotNil(point["date"] as? String)

        let sleep = ms.first { $0["name"] as? String == "sleep_analysis" }!
        let srec = (sleep["data"] as! [[String: Any]])[0]
        XCTAssertEqual(srec["deep"] as? Double, 1.2)
        XCTAssertEqual(srec["core"] as? Double, 4)
        XCTAssertEqual(srec["totalSleep"] as! Double, 6.7, accuracy: 0.001)  // deep+core+rem
        XCTAssertNotNil(srec["sleepEnd"] as? String)
        XCTAssertNil(srec["awake"])   // awake was nil → omitted
    }

    func testEmptyRecordsProduceNoMetrics() {
        XCTAssertTrue(metrics(HealthIngestClient.payload([])).isEmpty)
    }

    func testGaugeOnlyRecordEmitsNoSleep() {
        var r = DailyRecovery(day: day(2026, 7, 21))
        r.bodyMassKg = 80
        let names = metrics(HealthIngestClient.payload([r])).compactMap { $0["name"] as? String }
        XCTAssertEqual(names, ["weight_body_mass"])   // no sleep_analysis
    }
}
