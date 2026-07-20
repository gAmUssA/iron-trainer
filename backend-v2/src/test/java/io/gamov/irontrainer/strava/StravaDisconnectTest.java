package io.gamov.irontrainer.strava;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** POST /api/strava/disconnect — port of strava_router.disconnect. In local
 * (non-auth) mode the current athlete is the default; when nothing is connected,
 * deauthorize is skipped and the local purge still runs and reports a summary.
 * WireMockStravaLogin keeps any refresh/deauthorize attempt off the real network. */
@QuarkusTest
@QuarkusTestResource(value = WireMockStravaLogin.class, restrictToAnnotatedClass = true)
class StravaDisconnectTest {

    @Test
    void disconnectPurgesAndReportsSummary() {
        given()
                .when().post("/api/strava/disconnect")
                .then().statusCode(200)
                // The three deletion counts are always present integers (§2.5 summary).
                .body("deleted_activities", instanceOf(Integer.class))
                .body("deleted_metrics", instanceOf(Integer.class))
                .body("revoked_devices", instanceOf(Integer.class))
                .body("deauthorized", instanceOf(Boolean.class))
                .body("message", is(
                        "Disconnected. Your Strava activities and derived data have been deleted."));
    }
}
