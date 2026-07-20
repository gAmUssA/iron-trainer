package io.gamov.irontrainer.athlete;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PUT /api/athlete/profile — validated edit with exclude_unset semantics; a
 * threshold change recomputes TSS + rebuilds metrics + refreshes future plan
 * targets (0 here — no active plan). Dedicated athlete 7001. */
@QuarkusTest
@TestProfile(ProfilePutTest.Profile.class)
class ProfilePutTest {

    static final int AID = 7001;

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
        });
    }

    @Test
    void updateExcludeUnsetAndNullClear() {
        // Set thresholds → values applied; a threshold change reports the refresh.
        given().contentType("application/json")
                .body("{\"ftp\":250.0,\"threshold_hr\":160,\"gi_tolerance\":\"high\"}")
                .when().put("/api/athlete/profile")
                .then().statusCode(200)
                .body("connected", is(false))
                .body("profile.ftp", is(250.0f))
                .body("profile.threshold_hr", is(160))
                .body("profile.gi_tolerance", is("high"))
                .body("plan_weeks_refreshed", is(0));   // no active plan

        // exclude_unset: sending only ftp leaves threshold_hr + gi_tolerance intact.
        given().contentType("application/json").body("{\"ftp\":255.0}")
                .when().put("/api/athlete/profile")
                .then().statusCode(200)
                .body("profile.ftp", is(255.0f))
                .body("profile.threshold_hr", is(160))
                .body("profile.gi_tolerance", is("high"));

        // explicit null clears the field.
        given().contentType("application/json").body("{\"ftp\":null}")
                .when().put("/api/athlete/profile")
                .then().statusCode(200)
                .body("profile.ftp", nullValue());
    }

    @Test
    void validationRejectsOutOfBoundsAndBadEnum() {
        for (String bad : new String[]{
                "{\"ftp\":-5}", "{\"ftp\":2000}", "{\"threshold_hr\":40}",
                "{\"max_hr\":150.5}", "{\"gi_tolerance\":\"nope\"}", "{\"sweat_rate_l_h\":9}",
                "{\"threshold_hr\":true}"}) {   // bool rejected for int fields (pydantic)
            given().contentType("application/json").body(bad)
                    .when().put("/api/athlete/profile")
                    .then().statusCode(422);
        }
    }

    @Test
    void pydanticLaxCoercions() {
        // Numeric strings coerce (pydantic lax): "255"→255, "162"→162.
        given().contentType("application/json").body("{\"ftp\":\"255\",\"threshold_hr\":\"162\"}")
                .when().put("/api/athlete/profile")
                .then().statusCode(200)
                .body("profile.ftp", is(255.0f))
                .body("profile.threshold_hr", is(162));
        // A bool coerces to a float for FLOAT fields (true→1.0 passes gt=0).
        given().contentType("application/json").body("{\"weekly_hours_target\":true}")
                .when().put("/api/athlete/profile")
                .then().statusCode(200)
                .body("profile.weekly_hours_target", is(1.0f));
    }

    @Test
    void emptyAndNonObjectBodyAre422() {
        given().contentType("application/json").body("")
                .when().put("/api/athlete/profile").then().statusCode(422);
        given().contentType("application/json").body("[]")
                .when().put("/api/athlete/profile").then().statusCode(422);
        given().contentType("application/json").body("\"x\"")
                .when().put("/api/athlete/profile").then().statusCode(422);
    }
}
