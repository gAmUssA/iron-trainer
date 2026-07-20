package io.gamov.irontrainer.app;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

/** The app-level tail ported from FastAPI: /api/health, /api/status, /api/me,
 * /api/auth/logout, /api/athlete. */
@QuarkusTest
class StatusEndpointsTest {

    @Test
    void healthLiveness() {
        given().when().get("/api/health")
                .then().statusCode(200)
                .body("status", is("ok"))
                .body("version", is("0.1.0"))
                .body("$", org.hamcrest.Matchers.not(hasKey("database")));
    }

    @Test
    void healthDeepChecksDb() {
        given().when().get("/api/health?deep=1")
                .then().statusCode(200)
                .body("status", is("ok"))
                .body("database", is("ok"));
    }

    @Test
    void statusShape() {
        given().when().get("/api/status")
                .then().statusCode(200)
                .body("version", is("0.1.0"))
                .body("race", hasKey("name"))
                .body("race", hasKey("date"))
                .body("race", hasKey("distance"))
                .body("strava_configured", instanceOf(Boolean.class))
                .body("anthropic_configured", instanceOf(Boolean.class))
                .body("auth_required", instanceOf(Boolean.class))
                .body("authenticated", instanceOf(Boolean.class));
    }

    @Test
    void meShape() {
        given().when().get("/api/me")
                .then().statusCode(200)
                .body("authenticated", instanceOf(Boolean.class))
                .body("auth_required", instanceOf(Boolean.class))
                .body("$", hasKey("athlete"));
    }

    @Test
    void athleteProfileShape() {
        given().when().get("/api/athlete")
                .then().statusCode(200)
                .body("connected", instanceOf(Boolean.class))
                .body("profile", hasKey("ftp"))
                .body("profile", hasKey("gi_tolerance"))
                .body("profile", hasKey("updated_at"))
                .body("profile", hasKey("strava_athlete_id"));
    }

    @Test
    void logoutWithoutSessionSetsNoCookie() {
        Response r = given().when().post("/api/auth/logout")
                .then().statusCode(200)
                .body("ok", is(true))
                .extract().response();
        // Bearer/no-session client → Starlette (and we) emit no Set-Cookie.
        org.hamcrest.MatcherAssert.assertThat(r.getHeader("Set-Cookie"), nullValue());
    }

    @Test
    void logoutWithSessionDeletesCookie() {
        given().cookie("session", "whatever").when().post("/api/auth/logout")
                .then().statusCode(200)
                .body("ok", is(true))
                .header("Set-Cookie", anyOf(
                        containsString("session=null"),
                        containsString("expires=Thu, 01 Jan 1970")));
    }
}
