package io.gamov.irontrainer.strava;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

/** GET /api/strava/callback in LOCAL (non-auth) mode — port of
 * strava_router.callback's local branch. WireMockStrava's /oauth/token returns a
 * token without an athlete block (matches a refresh-shaped stub), which is all
 * the local path needs: it attaches the tokens to the default athlete. */
@QuarkusTest
@QuarkusTestResource(value = WireMockStrava.class, restrictToAnnotatedClass = true)
class StravaCallbackTest {

    // Strava bounced the user back cancelled → SPA banner, no token exchange.
    @Test
    void deniedRedirectsToSpaWithError() {
        given().redirects().follow(false)
                .when().get("/api/strava/callback?error=access_denied")
                .then().statusCode(307)
                .header("Location", "http://localhost:5173/?strava_error=access_denied");
    }

    // No code and no error (shouldn't happen, but Python guards it) → no_code.
    @Test
    void missingCodeRedirectsNoCode() {
        given().redirects().follow(false)
                .when().get("/api/strava/callback")
                .then().statusCode(307)
                .header("Location", "http://localhost:5173/?strava_error=no_code");
    }

    // Happy local path: exchange the code, attach tokens to the default athlete,
    // redirect connected=1. No session cookie is minted in local mode.
    @Test
    void codeExchangeConnectsAndRedirects() {
        Response r = given().redirects().follow(false)
                .when().get("/api/strava/callback?code=abc123&state=whatever")
                .then().statusCode(307)
                .header("Location", "http://localhost:5173/?connected=1")
                .extract().response();
        assertNull(r.getCookie("session"), "local mode mints no session cookie");
    }
}
