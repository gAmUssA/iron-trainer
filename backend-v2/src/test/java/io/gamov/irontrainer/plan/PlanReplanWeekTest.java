package io.gamov.irontrainer.plan;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;

/** POST /api/plan/replan-week — regenerate one week (template mode in test, no
 * LLM key). Byte-parity vs FastAPI is gated by the Python contract test. */
@QuarkusTest
class PlanReplanWeekTest {

    private String firstWeekStart() {
        given().when().post("/api/v2/athletes").then().statusCode(200);
        given().when().post("/api/plan/generate?use_llm=false").then().statusCode(200);
        return given().when().get("/api/plan").then().statusCode(200)
                .extract().path("plan.weeks[0].week_start");
    }

    @Test
    void replanReplacesTheWeek() {
        String ws = firstWeekStart();
        given().when().post("/api/plan/replan-week?week_start=" + ws + "&use_llm=false").then()
                .statusCode(200)
                .body("week_start", equalTo(ws))
                .body("llm_used", equalTo(false))
                .body("workouts", greaterThan(0))          // template → 6 sessions for the week
                .body("notes", instanceOf(java.util.List.class));
    }

    @Test
    void unknownWeekIs400() {
        firstWeekStart();
        // A Monday not in the plan → ValueError → 400.
        given().when().post("/api/plan/replan-week?week_start=2000-01-03&use_llm=false").then()
                .statusCode(400);
    }

    @Test
    void missingWeekStartIs422() {
        given().when().post("/api/v2/athletes").then().statusCode(200);
        given().when().post("/api/plan/replan-week?use_llm=false").then().statusCode(422);
    }
}
