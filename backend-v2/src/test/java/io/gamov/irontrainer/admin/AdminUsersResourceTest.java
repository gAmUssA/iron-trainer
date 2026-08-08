package io.gamov.irontrainer.admin;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** Admin users view (bean y8b2): guard + list. Asserts the response never leaks
 * Strava tokens (only a derived `connected` flag). */
@QuarkusTest
class AdminUsersResourceTest {

    @Test
    void usersRequiresAdminSession() {
        given().when().get("/api/admin/users").then().statusCode(401);
    }

    @Test
    void loginThenUsersWithCookieSucceedsAndHidesTokens() {
        String setCookie = given().contentType("application/json").body("{\"password\":\"test-admin-pw\"}")
                .when().post("/api/admin/login")
                .then().statusCode(200)
                .extract().header("Set-Cookie");
        String cookie = setCookie.split(";", 2)[0];

        String body = given().header("Cookie", cookie)
                .when().get("/api/admin/users")
                .then().statusCode(200)
                .body("users", notNullValue())
                .extract().asString();

        // Never expose Strava OAuth tokens through the admin surface.
        assert !body.contains("strava_access_token") : "admin users leaked strava_access_token";
        assert !body.contains("strava_refresh_token") : "admin users leaked strava_refresh_token";
    }
}
