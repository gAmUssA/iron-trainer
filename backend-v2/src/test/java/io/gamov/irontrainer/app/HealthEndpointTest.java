package io.gamov.irontrainer.app;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.gamov.irontrainer.athlete.Athlete;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** POST /api/health/ingest + GET /api/health/recovery. Uses a dedicated default
 * athlete (6001) so the daily_recovery FK is satisfied. */
@QuarkusTest
@TestProfile(HealthEndpointTest.Profile.class)
class HealthEndpointTest {

    static final int AID = 6001;

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("irontrainer.default-athlete-id", String.valueOf(AID));
        }
    }

    @BeforeEach
    void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (Athlete.findById(AID) == null) {
                Athlete.getEntityManager()
                        .createNativeQuery("INSERT INTO athlete (id) VALUES (" + AID + ")").executeUpdate();
            }
        });
    }

    @Test
    void ingestStoresRecoveryAndReadsItBack() {
        String payload = "{\"data\":{\"metrics\":["
                + "{\"name\":\"heart_rate_variability\",\"units\":\"ms\",\"data\":["
                + "{\"date\":\"2026-07-13 07:00:00 -0400\",\"qty\":55},"
                + "{\"date\":\"2026-07-13 08:00:00 -0400\",\"qty\":65}]},"
                + "{\"name\":\"resting_heart_rate\",\"units\":\"bpm\",\"data\":["
                + "{\"date\":\"2026-07-13 06:00:00 -0400\",\"qty\":48}]},"
                + "{\"name\":\"mystery_metric\",\"data\":["
                + "{\"date\":\"2026-07-13 06:00:00 -0400\",\"qty\":1}]}"
                + "]}}";
        given().contentType("application/json").body(payload)
                .when().post("/api/health/ingest")
                .then().statusCode(200)
                .body("ok", is(true))
                .body("days", is(1))
                .body("parsed.records", is(4))   // 2 HRV + 1 RHR + 1 mystery data records
                .body("parsed.bad_dates", is(0))
                .body("parsed.unknown_metrics[0]", is("mystery_metric"));

        given().when().get("/api/health/recovery?days=5")
                .then().statusCode(200)
                .body("days[0].date", is("2026-07-13"))
                .body("days[0].hrv_ms", is(60.0f))   // (55+65)/2
                .body("days[0].rhr_bpm", is(48.0f));
    }

    @Test
    void malformedJsonReturnsOkFalse() {
        given().contentType("application/json").body("{not valid json")
                .when().post("/api/health/ingest")
                .then().statusCode(200)
                .body("ok", is(false))
                .body("error", is("invalid JSON"))
                .body("days", is(0));
    }
}
