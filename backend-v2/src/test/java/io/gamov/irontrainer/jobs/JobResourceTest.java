package io.gamov.irontrainer.jobs;

import io.quarkus.test.junit.QuarkusTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/** End-to-end async envelope: POST ?async=1 returns {"job": <dict>}, the job
 * runs on a virtual thread, and GET /api/jobs/{id} polls to a terminal result.
 * Uses nutrition regenerate (no ANTHROPIC_API_KEY in test → deterministic
 * fallback, so the job always succeeds). Also guards the 404 tenancy behavior. */
@QuarkusTest
class JobResourceTest {

    @Test
    void asyncRegenerateReturnsJobEnvelopeThatPollsToSucceeded() {
        given().when().post("/api/v2/athletes").then().statusCode(200);

        // Submit async → {"job": {id, kind, status, ...}}.
        Integer jobId = given().when()
                .post("/api/nutrition/race-day/regenerate?async=1").then()
                .statusCode(200)
                .body("job.id", notNullValue())
                .body("job.kind", equalTo("nutrition_regen"))
                .extract().path("job.id");

        // Poll the job to a terminal succeeded, with the plan as result.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                given().when().get("/api/jobs/" + jobId).then()
                        .statusCode(200)
                        .body("status", equalTo("succeeded"))
                        .body("result.llm_used", equalTo(false)));
    }

    @Test
    void missingJobIs404() {
        given().when().post("/api/v2/athletes").then().statusCode(200);
        given().when().get("/api/jobs/99999999").then().statusCode(404);
    }

    @Test
    void summaryHasLatestAndActiveKeys() {
        given().when().post("/api/v2/athletes").then().statusCode(200);
        given().when().get("/api/jobs/summary").then()
                .statusCode(200)
                .body("latest", notNullValue())
                .body("active", notNullValue());
    }
}
