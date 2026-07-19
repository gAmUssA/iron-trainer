package io.gamov.irontrainer.plan;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;

/** POST /api/plan/reconcile end-to-end (template mode). Byte-parity vs FastAPI is
 * gated by the Python contract test. */
@QuarkusTest
class PlanReconcileResourceTest {

    private void generatePlan() {
        given().when().post("/api/v2/athletes").then().statusCode(200);
        given().when().post("/api/plan/generate?use_llm=false").then().statusCode(200);
    }

    @Test
    void reconcileReturnsMatchedComplianceAndReplan() {
        generatePlan();
        given().when().post("/api/plan/reconcile?weeks_ahead=1&use_llm=false").then()
                .statusCode(200)
                .body("matched.completed", notNullValue())
                .body("matched.skipped", notNullValue())
                .body("matched.upcoming", notNullValue())
                .body("compliance.window_days", org.hamcrest.Matchers.equalTo(21))
                .body("weeks_replanned", instanceOf(java.util.List.class))
                .body("replanned", instanceOf(java.util.List.class))
                .body("form_flag", notNullValue());
    }

    @Test
    void weeksAheadOutOfRangeIs422() {
        generatePlan();
        given().when().post("/api/plan/reconcile?weeks_ahead=5&use_llm=false").then().statusCode(422);
        given().when().post("/api/plan/reconcile?weeks_ahead=0&use_llm=false").then().statusCode(422);
        given().when().post("/api/plan/reconcile?weeks_ahead=abc&use_llm=false").then().statusCode(422);
    }
}
