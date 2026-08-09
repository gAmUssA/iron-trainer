package io.gamov.irontrainer.app;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.gamov.irontrainer.health.HealthIngestLog;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** POST /api/health/ingest writes an audit row (bean j05e) even on the malformed
 * path, tagged with the detected source — the ingest itself is unaffected. */
@QuarkusTest
class HealthIngestWriteTest {

    @Test
    void malformedBodyStillLogsAnIngestRow() {
        given().contentType("application/json")
                .header("X-Ingest-Client", "hae")
                .body("{ this is not json")
                .when().post("/api/health/ingest")
                .then().statusCode(200)
                .body("ok", equalTo(false));

        // The most recent row is this test's write (tests run sequentially).
        HealthIngestLog latest = QuarkusTransaction.requiringNew()
                .call(() -> HealthIngestLog.find("order by id desc").firstResult());
        assert latest != null : "no health_ingest_log row written";
        assert "hae".equals(latest.source) : "source should be hae, got " + latest.source;
        assert Boolean.FALSE.equals(latest.ok) : "malformed ingest should log ok=false";
        // NOTE: athlete_id is whatever current.idOrNull() resolves — in prod an
        // unauthenticated post is null, but the test env resolves a default athlete,
        // so we don't assert on it here.
        assert "invalid JSON".equals(latest.error) : "error should be recorded, got " + latest.error;
    }

    @Test
    void validPayloadLogsSuccessRowWithCounts() {
        // One RHR record for one day → records=1, one day stored, nothing unknown/bad.
        String payload = "{\"data\":{\"metrics\":[{\"name\":\"resting_heart_rate\",\"units\":\"count/min\","
                + "\"data\":[{\"date\":\"2026-08-01 07:00:00 +0000\",\"qty\":50}]}]}}";

        given().contentType("application/json")
                .header("X-Ingest-Client", "native")
                .body(payload)
                .when().post("/api/health/ingest")
                .then().statusCode(200)
                .body("ok", equalTo(true))
                .body("parsed.records", equalTo(1));

        HealthIngestLog latest = QuarkusTransaction.requiringNew()
                .call(() -> HealthIngestLog.find("order by id desc").firstResult());
        assert latest != null : "no audit row written for the success path";
        assert Boolean.TRUE.equals(latest.ok) : "success path should log ok=true";
        assert "native".equals(latest.source) : "source should be native, got " + latest.source;
        assert latest.records != null && latest.records == 1 : "records should be 1, got " + latest.records;
        assert latest.unknownMetrics != null && latest.unknownMetrics == 0 : "unknown_metrics should be 0";
        assert latest.badDates != null && latest.badDates == 0 : "bad_dates should be 0";
        assert latest.daysStored != null && latest.daysStored >= 1 : "days_stored should be >= 1, got " + latest.daysStored;
        assert latest.byteSize != null && latest.byteSize > 0 : "byte_size should be recorded";
        assert latest.error == null : "success path should have no error";
    }
}
