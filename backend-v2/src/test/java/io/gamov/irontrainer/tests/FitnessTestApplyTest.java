package io.gamov.irontrainer.tests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.plan.Plan;
import io.gamov.irontrainer.plan.PlannedWorkout;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** record → apply, the Tests-tab cascade (bean uyqq). Apply must write the
 * computed thresholds onto the athlete AND run the same future-plan-target
 * refresh the Settings profile PUT runs — otherwise a test-measured FTP never
 * reaches upcoming workout targets. Dedicated athlete 7101. */
@QuarkusTest
@TestProfile(FitnessTestApplyTest.Profile.class)
class FitnessTestApplyTest {

    static final int AID = 7101;

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("irontrainer.default-athlete-id", String.valueOf(AID));
        }
    }

    /** The Monday AFTER this one — refreshFuture skips any week whose start is
     * <= the current Monday, so the fixture week must be strictly future. */
    private static final String FUTURE_WEEK =
            LocalDate.now().with(DayOfWeek.MONDAY).plusDays(7).toString();

    /** Athlete on a STALE FTP with an active plan holding one future week. The
     * stale value matters: the assertion is that the refresh regenerates targets
     * from the newly applied 228 W, which is only observable if the pre-apply
     * value was different. */
    @BeforeEach
    void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (Athlete.findById(AID) == null) {
                Athlete.getEntityManager()
                        .createNativeQuery("INSERT INTO athlete (id) VALUES (" + AID + ")").executeUpdate();
            }
            Athlete a = Athlete.findById(AID);
            a.ftp = 200.0;
            PlannedWorkout.delete("athleteId", AID);
            Plan.delete("athleteId", AID);
            Plan p = new Plan();
            p.athleteId = AID;
            p.status = "active";
            p.raceName = "T";
            p.raceDate = "2026-09-26";
            p.baseWeeklyHours = 8.0;
            p.weeksJson = "[{\"week_start\": \"" + FUTURE_WEEK
                    + "\", \"phase\": \"base\", \"target_hours\": 8.0}]";
            p.persist();
        });
    }

    @Test
    void applyWritesThresholdsAndRegeneratesFutureBikeTargets() {
        // 20-min FTP test: 240 W → FTP = 95% = 228.
        int id = given().contentType("application/json")
                .body("{\"test_slug\":\"bike-ftp-20\",\"date\":\"2026-01-05\","
                        + "\"inputs\":{\"avg_power_w\":240}}")
                .when().post("/api/tests/result")
                .then().statusCode(200)
                .body("result.ftp", is(228))
                .body("applied", is(false))
                .extract().path("id");

        // Recording alone must NOT touch the profile.
        assertEquals(200.0, ftp(), 1e-9, "recording a test must not write thresholds");

        given().when().post("/api/tests/result/" + id + "/apply")
                .then().statusCode(200)
                .body("applied", is(true))
                .body("plan_weeks_refreshed", is(1));

        assertNotNull(ftp(), "apply must write the computed FTP to the profile");
        assertEquals(228.0, ftp(), 1e-9);

        // The load-bearing assertion. A "base"-phase week makes the hard bike
        // session TEMPO, whose band is [0.76, 0.87] x FTP — so the highest power
        // target is 198 W at the new FTP and 174 W at the stale one. refreshFuture
        // opens its OWN transaction, so if the threshold write had not COMMITTED
        // first (the regression: applyResult used to be @Transactional) it would
        // read 200 and regenerate 174 here — green endpoint, silently stale plan.
        assertEquals(198, bikeTargetHigh(),
                "future bike targets must be regenerated from the COMMITTED new FTP");
    }

    /** Highest power target across the future week's bike workouts. */
    private static int bikeTargetHigh() {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<PlannedWorkout> ws = PlannedWorkout.list(
                    "athleteId = ?1 and sport = 'Bike' and date >= ?2", AID, FUTURE_WEEK);
            org.junit.jupiter.api.Assertions.assertFalse(
                    ws.isEmpty(), "refresh should have written future bike workouts");
            int high = 0;
            for (PlannedWorkout w : ws) {
                for (JsonNode step : new ObjectMapper().readTree(w.structureJson)) {
                    JsonNode t = step.path("target");
                    if ("power".equals(t.path("type").asText()) && t.path("high").isNumber()) {
                        high = Math.max(high, t.path("high").asInt());
                    }
                }
            }
            return high;
        });
    }

    @Test
    void applyUnknownResultIs404() {
        given().when().post("/api/tests/result/98765432/apply").then().statusCode(404);
    }

    private static Double ftp() {
        return QuarkusTransaction.requiringNew().call(() -> Athlete.<Athlete>findById(AID).ftp);
    }
}
