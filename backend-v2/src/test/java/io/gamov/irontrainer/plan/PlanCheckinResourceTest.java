package io.gamov.irontrainer.plan;

import io.quarkus.test.junit.QuarkusTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;

/** POST /api/plan/checkin end-to-end (template mode, no Strava → not-connected).
 * Byte-parity vs FastAPI is gated by the Python contract test. */
@QuarkusTest
class PlanCheckinResourceTest {

    private void generatePlan() {
        given().when().post("/api/v2/athletes").then().statusCode(200);
        given().when().post("/api/plan/generate?use_llm=false").then().statusCode(200);
    }

    @Test
    void checkinComposesTheLoop() {
        generatePlan();
        given().contentType("application/json").body("{\"inputs\": {\"energy\": 3, \"sleep\": 4}}")
                .when().post("/api/plan/checkin?use_llm=false").then()
                .statusCode(200)
                .body("status", equalTo("ok"))
                .body("story", instanceOf(java.util.List.class))
                .body("reconcile.matched", notNullValue())
                .body("next_week.week_start", notNullValue())
                .body("readiness", notNullValue())
                .body("tests_due", instanceOf(java.util.List.class))
                .body("key_sessions", instanceOf(java.util.List.class))
                // no Strava token in test → not connected, degrades gracefully.
                .body("synced", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void checkinWithoutBodyWorks() {
        generatePlan();
        given().contentType("application/json").when().post("/api/plan/checkin?use_llm=false").then()
                .statusCode(200)
                .body("status", equalTo("ok"))
                .body("inputs", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void nonDictInputsIs422() {
        generatePlan();
        // CheckinBody.inputs is dict|None — a string value is a 422 in FastAPI.
        given().contentType("application/json").body("{\"inputs\": \"3\"}")
                .when().post("/api/plan/checkin?use_llm=false").then().statusCode(422);
    }

    @Test
    void asyncCheckinReturnsJob() {
        generatePlan();
        Integer jobId = given().contentType("application/json").when().post("/api/plan/checkin?use_llm=false&async=1").then()
                .statusCode(200)
                .body("job.kind", equalTo("checkin"))
                .extract().path("job.id");
        await().atMost(Duration.ofSeconds(25)).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                given().when().get("/api/jobs/" + jobId).then()
                        .statusCode(200)
                        .body("status", equalTo("succeeded"))
                        .body("result.status", equalTo("ok")));
    }
}
