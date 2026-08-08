package io.gamov.irontrainer.admin;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.gamov.irontrainer.health.HealthIngestLog;
import io.gamov.irontrainer.util.PyJson;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** Admin health-ingest feed (bean j05e): guard, filters (source/ok), and window
 * exclusion. Seeds inside the asserting test (not @BeforeEach) with a unique
 * athlete id so counts stay deterministic on the shared test DB. */
@QuarkusTest
class AdminIngestsResourceTest {

    static final int AID = 770101;

    static void log(String receivedAt, String source, boolean ok) {
        HealthIngestLog l = new HealthIngestLog();
        l.athleteId = AID;
        l.source = source;
        l.ok = ok;
        l.receivedAt = receivedAt;
        l.daysStored = ok ? 1 : 0;
        l.records = 5;
        l.unknownMetrics = 0;
        l.badDates = ok ? 0 : 1;
        l.byteSize = 100;
        l.persist();
    }

    static String adminCookie() {
        String setCookie = given().contentType("application/json").body("{\"password\":\"test-admin-pw\"}")
                .when().post("/api/admin/login").then().statusCode(200)
                .extract().header("Set-Cookie");
        return setCookie.split(";", 2)[0];
    }

    @Test
    void ingestsRequiresAdminSession() {
        given().when().get("/api/admin/health/ingests").then().statusCode(401);
    }

    @Test
    void windowExcludesAncientAndFiltersWork() {
        String now = PyJson.utcNowIso();
        String ancient = "2020-01-01T00:00:00.000000+00:00";
        QuarkusTransaction.requiringNew().run(() -> {
            log(ancient, "hae", true);   // out-of-window (inserted first: not the group max)
            log(now, "hae", true);
            log(now, "native", true);
            log(now, "hae", false);
        });

        // athlete-scoped + 7d window → the 3 recent rows, ancient excluded.
        given().header("Cookie", adminCookie())
                .when().get("/api/admin/health/ingests?athlete_id=" + AID + "&days=7")
                .then().statusCode(200)
                .body("total", equalTo(3))
                .body("last_by_source", notNullValue());

        // source filter.
        given().header("Cookie", adminCookie())
                .when().get("/api/admin/health/ingests?athlete_id=" + AID + "&source=hae&days=7")
                .then().statusCode(200)
                .body("total", equalTo(2));

        // ok filter.
        given().header("Cookie", adminCookie())
                .when().get("/api/admin/health/ingests?athlete_id=" + AID + "&ok=false&days=7")
                .then().statusCode(200)
                .body("total", equalTo(1));

        // wide window includes the ancient row too (4 total for this athlete).
        given().header("Cookie", adminCookie())
                .when().get("/api/admin/health/ingests?athlete_id=" + AID + "&days=3650")
                .then().statusCode(200)
                .body("total", equalTo(4));
    }
}
