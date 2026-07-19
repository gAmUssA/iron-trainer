package io.gamov.irontrainer.plan;

import io.gamov.irontrainer.activity.Activity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Reconcile.matchWorkouts: past workouts → completed (matched activity ±1 day)
 * or skipped; future → upcoming. Fixed dates via the `today` arg. */
@QuarkusTest
class ReconcileTest {

    @Test
    void matchesCompletedSkippedUpcoming() {
        given(); // ensure athlete 1
        LocalDate today = LocalDate.parse("2026-06-15");
        int[] ids = QuarkusTransaction.requiringNew().call(() -> {
            Plan p = new Plan();
            p.athleteId = 1; p.status = "active"; p.createdAt = "2026-06-01T10:00:00+00:00";
            p.persist();
            // Bike (past) with a same-day Bike activity → completed.
            PlannedWorkout bike = workout(p.id, "2026-06-10", "Bike");
            // Run (past) with no activity → skipped.
            PlannedWorkout run = workout(p.id, "2026-06-11", "Run");
            // Swim (future) → upcoming, stays planned.
            PlannedWorkout swim = workout(p.id, "2026-06-20", "Swim");
            Activity a = new Activity();
            a.id = 888001L; a.athleteId = 1; a.sport = "Bike";
            a.startDate = "2026-06-10T07:00:00+00:00"; a.isDuplicate = 0; a.tss = 70.0; a.movingTime = 3600;
            a.persist();
            return new int[] {p.id, bike.id, run.id, swim.id};
        });

        Map<String, Object> matched = QuarkusTransaction.requiringNew()
                .call(() -> Reconcile.matchWorkouts(1, ids[0], today));
        assertEquals(1, matched.get("completed"));
        assertEquals(1, matched.get("skipped"));
        assertEquals(1, matched.get("upcoming"));

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals("completed", ((PlannedWorkout) PlannedWorkout.findById(ids[1])).status);
            assertEquals(888001L, ((PlannedWorkout) PlannedWorkout.findById(ids[1])).matchedActivityId);
            assertEquals("skipped", ((PlannedWorkout) PlannedWorkout.findById(ids[2])).status);
            assertEquals("planned", ((PlannedWorkout) PlannedWorkout.findById(ids[3])).status);
        });
    }

    private static void given() {
        io.restassured.RestAssured.given().when().post("/api/v2/athletes").then().statusCode(200);
    }

    private static PlannedWorkout workout(int planId, String date, String sport) {
        PlannedWorkout w = new PlannedWorkout();
        w.athleteId = 1; w.planId = planId; w.date = date; w.sport = sport;
        w.status = "planned"; w.createdAt = "2026-06-01T10:00:00+00:00";
        w.persist();
        return w;
    }
}
