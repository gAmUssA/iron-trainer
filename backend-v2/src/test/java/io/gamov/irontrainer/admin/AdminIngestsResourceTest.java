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
        // ~100 days old: outside the 7d window but inside the endpoint's 365d clamp,
        // so a wide window can still include it.
        String old = PyJson.utcIsoDaysAgo(100);
        QuarkusTransaction.requiringNew().run(() -> {
            log(old, "hae", true);   // out-of-7d-window (inserted first: not the group max)
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

        // wide window (within the 365d clamp) includes the ~100-day-old row too.
        given().header("Cookie", adminCookie())
                .when().get("/api/admin/health/ingests?athlete_id=" + AID + "&days=365")
                .then().statusCode(200)
                .body("total", equalTo(4));
    }
}
