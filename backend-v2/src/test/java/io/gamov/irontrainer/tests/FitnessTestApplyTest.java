package io.gamov.irontrainer.tests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.gamov.irontrainer.athlete.Athlete;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
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

    @BeforeEach
    void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (Athlete.findById(AID) == null) {
                Athlete.getEntityManager()
                        .createNativeQuery("INSERT INTO athlete (id) VALUES (" + AID + ")").executeUpdate();
            }
            Athlete a = Athlete.findById(AID);
            a.ftp = null;
        });
    }

    @Test
    void applyWritesThresholdsAndRefreshesPlanTargets() {
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
        assertNull(ftp(), "recording a test must not write thresholds");

        given().when().post("/api/tests/result/" + id + "/apply")
                .then().statusCode(200)
                .body("applied", is(true))
                // The refresh ran (0 weeks — this athlete has no active plan).
                .body("plan_weeks_refreshed", is(0));

        assertNotNull(ftp(), "apply must write the computed FTP to the profile");
        assertEquals(228.0, ftp(), 1e-9);
    }

    @Test
    void applyUnknownResultIs404() {
        given().when().post("/api/tests/result/98765432/apply").then().statusCode(404);
    }

    private static Double ftp() {
        return QuarkusTransaction.requiringNew().call(() -> Athlete.<Athlete>findById(AID).ftp);
    }
}
