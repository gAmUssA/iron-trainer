package io.gamov.irontrainer.plan;

import io.quarkus.test.junit.QuarkusTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;

/** POST /api/plan/generate end-to-end. No ANTHROPIC_API_KEY in test → the LLM
 * path falls back to the deterministic template (llm_used=false), and the plan
 * is built, validated, expanded, and saved. Byte-parity vs FastAPI is gated by
 * the Python contract test; the engine values are pinned in PlanTemplateTest. */
@QuarkusTest
class PlanGenerateResourceTest {

    @Test
    void generatesAndSavesDeterministicPlan() {
        given().when().post("/api/v2/athletes").then().statusCode(200);

        int weeks = given().when().post("/api/plan/generate?use_llm=false").then()
                .statusCode(200)
                .body("llm_used", equalTo(false))
                .body("plan_id", notNullValue())
                .body("weeks", greaterThan(0))
                .body("workouts", greaterThan(0))
                .body("adjustments", instanceOf(java.util.List.class))
                .body("summary", notNullValue())
                .extract().path("weeks");

        // 6 sessions per week (Swim/Bike/Run ×2), athlete has no weekly target → 6h.
        given().when().post("/api/plan/generate?use_llm=false").then()
                .statusCode(200)
                .body("workouts", equalTo(weeks * 6));

        // GET /api/plan reads back the saved active plan with structured workouts.
        given().when().get("/api/plan").then()
                .statusCode(200)
                .body("plan.status", equalTo("active"))
                .body("workouts[0].steps[0].type", equalTo("warmup"))
                .body("workouts[0].steps.size()", equalTo(3));
    }

    @Test
    void llmDefaultTrueStillFallsBackWithoutKey() {
        given().when().post("/api/v2/athletes").then().statusCode(200);
        // no use_llm param → defaults true → no key → deterministic fallback.
        given().when().post("/api/plan/generate").then()
                .statusCode(200)
                .body("llm_used", equalTo(false));
    }

    @Test
    void asyncGenerateReturnsJobThatSucceeds() {
        given().when().post("/api/v2/athletes").then().statusCode(200);
        Integer jobId = given().when().post("/api/plan/generate?use_llm=false&async=1").then()
                .statusCode(200)
                .body("job.kind", equalTo("generate_plan"))
                .body("job.id", notNullValue())
                .extract().path("job.id");

        await().atMost(Duration.ofSeconds(25)).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                given().when().get("/api/jobs/" + jobId).then()
                        .statusCode(200)
                        .body("status", equalTo("succeeded"))
                        .body("result.plan_id", notNullValue()));
    }
}
