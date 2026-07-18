package io.gamov.irontrainer.plan;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/** GET /api/plan shape parity: _plan_dict (weeks_json→weeks) + _workout_dict
 * (structure_json→steps), full field set. Byte-parity vs FastAPI is gated by the
 * Python contract test (test_plan_parity). */
@QuarkusTest
class PlanResourceTest {

    @Test
    void activePlanReturnsParsedWeeksAndWorkouts() {
        given().when().post("/api/v2/athletes").then().statusCode(200);
        // Seed an active plan + one workout for the default test athlete (id 1).
        QuarkusTransaction.requiringNew().run(() -> {
            Plan p = new Plan();
            p.athleteId = 1;
            p.status = "active";
            p.raceName = "IRONMAN 70.3 New York";
            p.raceDate = "2026-09-26";
            p.summary = "test season";
            p.weeksJson = "[{\"phase\": \"base\", \"target_tss\": 300}]";
            p.baseWeeklyHours = 8.0;
            p.createdAt = "2026-06-01T10:00:00+00:00";
            p.persist();

            PlannedWorkout w = new PlannedWorkout();
            w.athleteId = 1;
            w.planId = p.id;
            w.date = "2026-06-02";
            w.sport = "Bike";
            w.title = "Endurance ride";
            w.description = "Z2";
            w.intensity = "endurance";
            w.structureJson = "[{\"type\": \"steady\", \"minutes\": 60}]";
            w.durationS = 3600;
            w.distanceM = 30000.0;
            w.plannedTss = 60.0;
            w.status = "planned";
            w.createdAt = "2026-06-01T10:00:00+00:00";
            w.persist();
        });

        given().when().get("/api/plan").then()
                .statusCode(200)
                .body("plan.status", equalTo("active"))
                .body("plan.base_weekly_hours", is(8.0f))
                .body("plan.weeks[0].phase", equalTo("base"))
                .body("plan.weeks[0].target_tss", is(300))
                // weeks_json/structure_json are NOT leaked as raw columns.
                .body("plan", hasKey("created_at"))
                .body("workouts[0].sport", equalTo("Bike"))
                .body("workouts[0].duration_s", is(3600))
                .body("workouts[0].steps[0].type", equalTo("steady"))
                // full model_dump field set incl. the previously-missing columns:
                .body("workouts[0]", hasKey("fit_path"))
                .body("workouts[0]", hasKey("zwo_path"))
                .body("workouts[0].matched_activity_id", nullValue());
    }
}
