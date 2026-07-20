package io.gamov.irontrainer.export;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.gamov.irontrainer.athlete.Athlete;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Export ZIP bundles: filename/slug parity + the no-plan 404s + bad-date 500.
 * The happy-path bundle (entry names + README bytes) is cross-verified against
 * FastAPI in test_export_zip_parity — the .fit/.zwo entry bytes intentionally
 * differ (live FIT timestamp / ms durations / whitespace). */
@QuarkusTest
@TestProfile(ExportZipTest.Profile.class)
class ExportZipTest {

    static final int AID = 8001;

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("irontrainer.default-athlete-id", String.valueOf(AID));
        }
    }

    @BeforeEach
    void seedAthleteNoPlan() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (Athlete.findById(AID) == null) {
                Athlete.getEntityManager()
                        .createNativeQuery("INSERT INTO athlete (id) VALUES (" + AID + ")").executeUpdate();
            }
        });
    }

    @Test
    void slugMatchesPython() {
        assertEquals("Long-ride", ExportResource.slug("Long ride"));
        assertEquals("VO2-max-4x4", ExportResource.slug("  VO2 max 4x4!  "));
        assertEquals("workout", ExportResource.slug("!!!"));      // empties → "workout"
        assertEquals("workout", ExportResource.slug(null));
        assertEquals("a".repeat(40), ExportResource.slug("a".repeat(60)));   // ≤ 40 chars
    }

    @Test
    void noActivePlanIs404() {
        given().when().get("/api/export/plan.zip").then().statusCode(404);
        given().when().get("/api/export/week/2026-01-05.zip").then().statusCode(404);
        // Compact ISO (date.fromisoformat accepts it) parses → empty → 404, not 500.
        given().when().get("/api/export/week/20260105.zip").then().statusCode(404);
    }

    @Test
    void malformedWeekDateIs500() {
        given().when().get("/api/export/week/not-a-date.zip").then().statusCode(500);
    }
}
