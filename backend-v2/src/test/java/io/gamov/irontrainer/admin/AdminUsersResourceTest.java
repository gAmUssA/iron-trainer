package io.gamov.irontrainer.admin;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.jobs.Job;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Admin users view (bean y8b2): guard, token-leak protection, and detail
 * behavior (fields, counts, grouped last-job-per-kind, recent-10, 404). Test
 * profile sets irontrainer.admin-password=test-admin-pw + a fixed session secret. */
@QuarkusTest
class AdminUsersResourceTest {

    // Distinct sentinels so a leak under ANY key (not just the known token keys) is caught.
    static final String ACCESS_SENTINEL = "SENTINEL-ACCESS-a1b2c3";
    static final String REFRESH_SENTINEL = "SENTINEL-REFRESH-d4e5f6";
    // @BeforeEach runs per test and QuarkusTest shares the DB, so strava/apple ids
    // must be unique across seeds to avoid the athlete unique-index collisions.
    static final AtomicLong STRAVA_SEQ = new AtomicLong(900000);

    int athleteId;

    @BeforeEach
    void seed() {
        AtomicInteger idHolder = new AtomicInteger();
        QuarkusTransaction.requiringNew().run(() -> {
            Athlete a = new Athlete();
            a.name = "Sentinel User";
            a.stravaAthleteId = STRAVA_SEQ.incrementAndGet();
            a.stravaAccessToken = ACCESS_SENTINEL;
            a.stravaRefreshToken = REFRESH_SENTINEL;
            a.appleUserId = "apple-sentinel-" + java.util.UUID.randomUUID();
            a.persist();
            idHolder.set(a.id);

            // A rarely-run kind, inserted first → lowest id (its last run falls
            // outside the 10 newest jobs). The group-by must still surface it.
            job(a.id, "strava_gdpr", "succeeded");
            // 12 runs of a frequent kind, inserted after → higher ids; 2 failed.
            for (int i = 0; i < 12; i++) {
                job(a.id, "strava_sync", i < 2 ? "failed" : "succeeded");
            }
        });
        athleteId = idHolder.get();
    }

    static void job(int athleteId, String kind, String status) {
        Job j = new Job();
        j.athleteId = athleteId;
        j.kind = kind;
        j.status = status;
        j.createdAt = "2026-08-08T00:00:00";
        j.finishedAt = "2026-08-08T00:00:01";
        j.persist();
    }

    /** Login and return the "admin_session=..." cookie pair. */
    static String adminCookie() {
        String setCookie = given().contentType("application/json").body("{\"password\":\"test-admin-pw\"}")
                .when().post("/api/admin/login")
                .then().statusCode(200)
                .extract().header("Set-Cookie");
        return setCookie.split(";", 2)[0];
    }

    @Test
    void usersRequiresAdminSession() {
        given().when().get("/api/admin/users").then().statusCode(401);
        given().when().get("/api/admin/users/" + athleteId).then().statusCode(401);
    }

    @Test
    void usersListHidesTokens() {
        String body = given().header("Cookie", adminCookie())
                .when().get("/api/admin/users")
                .then().statusCode(200)
                .body("users", notNullValue())
                .extract().asString();
        assert !body.contains(ACCESS_SENTINEL) : "admin users list leaked the access token";
        assert !body.contains(REFRESH_SENTINEL) : "admin users list leaked the refresh token";
    }

    @Test
    void userDetailReportsFieldsAndHidesTokens() {
        String body = given().header("Cookie", adminCookie())
                .when().get("/api/admin/users/" + athleteId)
                .then().statusCode(200)
                .body("connected", equalTo(true))        // has a refresh token
                .body("apple_linked", equalTo(true))      // has an apple id
                .body("counts.jobs", equalTo(13))
                .body("recent_jobs", hasSize(10))          // capped at 10 of 13
                // group-by keeps the rarely-run kind even though its run is older
                // than the 10 most recent jobs.
                .body("last_sync.strava_gdpr", notNullValue())
                .body("last_sync.strava_sync", notNullValue())
                .extract().asString();
        assert !body.contains(ACCESS_SENTINEL) : "admin user detail leaked the access token";
        assert !body.contains(REFRESH_SENTINEL) : "admin user detail leaked the refresh token";
    }

    @Test
    void userDetailUnknownIdIs404() {
        given().header("Cookie", adminCookie())
                .when().get("/api/admin/users/999999")
                .then().statusCode(404);
    }
}
