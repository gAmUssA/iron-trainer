package io.gamov.irontrainer.plan;

import io.gamov.irontrainer.activity.Activity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/** GET /api/plan/compliance — planned-vs-actual per week + recent window.
 * Byte-parity vs FastAPI is gated by the Python contract test; this pins the
 * per-status math (completed/skipped/planned, matched-activity TSS/hours). */
@QuarkusTest
class ComplianceResourceTest {

    @Test
    void weeksAndRecentReflectStatuses() {
        given().when().post("/api/v2/athletes").then().statusCode(200);
        // All three sessions on one recent date → one week, all inside the 21d window.
        String d = LocalDate.now().minusDays(1).toString();
        QuarkusTransaction.requiringNew().run(() -> {
            Plan p = new Plan();
            p.athleteId = 1;
            p.status = "active";
            p.createdAt = "2026-06-01T10:00:00+00:00";
            p.persist();

            Activity a = new Activity();
            a.id = 999001L;
            a.athleteId = 1;
            a.sport = "Bike";
            a.startDate = d + "T08:00:00+00:00";
            a.isDuplicate = 0;
            a.tss = 55.0;
            a.movingTime = 3600;
            a.persist();

            addWorkout(p.id, d, "completed", 60.0, 3600, 999001L);
            addWorkout(p.id, d, "skipped", 40.0, 3600, null);
            addWorkout(p.id, d, "planned", 50.0, 3600, null);
        });

        given().when().get("/api/plan/compliance").then()
                .statusCode(200)
                // one week bucket, counts by status
                .body("weeks[0].completed", is(1))
                .body("weeks[0].skipped", is(1))
                .body("weeks[0].planned", is(1))
                .body("weeks[0].planned_tss", is(150.0f))   // 60+40+50
                .body("weeks[0].actual_tss", is(55.0f))     // matched activity
                .body("weeks[0].planned_hours", is(3.0f))   // 3×3600s
                .body("weeks[0].actual_hours", is(1.0f))
                // recent window
                .body("recent.window_days", is(21))
                .body("recent.planned_sessions", is(3))
                .body("recent.completed_sessions", is(1))
                .body("recent.skipped_sessions", is(1))
                .body("recent.completion_rate", is(0.33f))  // round(1/3, 2)
                .body("recent.planned_tss", is(150))        // round(150.0) → int
                .body("recent.actual_tss", is(55))
                .body("recent.load_ratio", is(0.37f));      // round(55/150, 2)
    }

    private static void addWorkout(int planId, String date, String status,
                                   double plannedTss, int durationS, Long matchedId) {
        PlannedWorkout w = new PlannedWorkout();
        w.athleteId = 1;
        w.planId = planId;
        w.date = date;
        w.sport = "Bike";
        w.status = status;
        w.plannedTss = plannedTss;
        w.durationS = durationS;
        w.matchedActivityId = matchedId;
        w.createdAt = "2026-06-01T10:00:00+00:00";
        w.persist();
    }
}
