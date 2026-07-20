package io.gamov.irontrainer.strava;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.gamov.irontrainer.auth.SessionCookie;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.util.Map;
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

    // Python truthiness: an empty ?code= counts as no code → no_code (not an
    // exchange attempt). Guards the `not code` vs `code == null` parity fix.
    @Test
    void emptyCodeRedirectsNoCode() {
        given().redirects().follow(false)
                .when().get("/api/strava/callback?code=")
                .then().statusCode(307)
                .header("Location", "http://localhost:5173/?strava_error=no_code");
    }

    // When the incoming session carried an oauth_state, the callback consumes it
    // and re-emits the cleared session cookie (Starlette parity — a mutated
    // session always produces a Set-Cookie), even in local mode.
    @Test
    void exchangeClearsConsumedOauthState() {
        String incoming = SessionCookie.sign(Map.of("oauth_state", "S9"), "test-secret-key");
        Response r = given().redirects().follow(false)
                .cookie("session", incoming)
                .when().get("/api/strava/callback?code=abc123&state=S9")
                .then().statusCode(307)
                .header("Location", "http://localhost:5173/?connected=1")
                .extract().response();
        String set = r.getHeader("Set-Cookie");
        assertNotNull(set, "a consumed oauth_state re-emits the cleared cookie");
        // The re-signed cookie no longer carries oauth_state.
        String value = set.startsWith("session=") ? set.substring(8, set.indexOf(';')) : null;
        Map<String, Object> session = SessionCookie.read(value, "test-secret-key");
        assertNotNull(session);
        assertFalse(session.containsKey("oauth_state"), "oauth_state is cleared");
    }
}
