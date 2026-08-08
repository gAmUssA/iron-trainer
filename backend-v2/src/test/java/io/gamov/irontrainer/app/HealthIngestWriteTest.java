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
        assert latest.athleteId == null : "unauthenticated malformed post has no athlete";
        assert "invalid JSON".equals(latest.error) : "error should be recorded, got " + latest.error;
    }
}
