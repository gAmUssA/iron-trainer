package io.gamov.irontrainer.strava;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.auth.SessionCookie;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** GET /api/strava/callback in DEPLOYMENT (auth-required) mode — the LOGIN path.
 * Verifies the oauth_state CSRF gate, the allowlist, and that a successful login
 * mints an athlete_id session (byte-identical minting) + persists the tokens.
 * The test secret is the fixed %test one (test-secret-key). */
@QuarkusTest
@TestProfile(StravaCallbackAuthTest.AuthProfile.class)
@QuarkusTestResource(value = WireMockStravaLogin.class, restrictToAnnotatedClass = true)
class StravaCallbackAuthTest {

    /** Deployment mode with an allowlist containing the WireMock athlete (424242)
     * plus an over-long numeric token — which must be kept (never matches) rather
     * than overflow a long and 500 every login (BigInteger parity fix). */
    public static class AuthProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "irontrainer.auth-required", "true",
                    "irontrainer.allowed-strava-ids", "424242,99999999999999999999999999");
        }
    }

    private static final String SECRET = "test-secret-key";

    // No matching oauth_state cookie → CSRF check fails before any exchange.
    @Test
    void mismatchedStateRejected() {
        given().redirects().follow(false)
                .when().get("/api/strava/callback?code=abc&state=STATE1")
                .then().statusCode(307)
                .header("Location", "http://localhost:5173/?strava_error=invalid_state");
    }

    // Full login: valid state → exchange → allowlist ok → mint athlete_id session
    // (oauth_state dropped) + persist tokens for the Strava athlete.
    @Test
    void validLoginMintsSessionAndSavesTokens() {
        String stateCookie = SessionCookie.sign(Map.of("oauth_state", "STATE1"), SECRET);
        Response r = given().redirects().follow(false)
                .cookie("session", stateCookie)
                .when().get("/api/strava/callback?code=abc&state=STATE1")
                .then().statusCode(307)
                .header("Location", "http://localhost:5173/?connected=1")
                .extract().response();

        // A fresh login session was minted, carrying athlete_id and NOT oauth_state.
        // Parse the raw Set-Cookie header (RestAssured's getCookie mangles the
        // itsdangerous value's '=' padding) — the same hand-parse a browser does.
        String set = sessionFromSetCookie(r.getHeader("Set-Cookie"));
        assertNotNull(set, "login must mint a session cookie");
        Map<String, Object> session = SessionCookie.read(set, SECRET);
        assertNotNull(session, "minted cookie must verify with the shared secret");
        assertTrue(session.containsKey("athlete_id"), "session carries the athlete id");
        assertFalse(session.containsKey("oauth_state"), "oauth_state is consumed");

        // The Strava athlete row exists with the exchanged tokens + name.
        Athlete a = QuarkusTransaction.requiringNew().call(
                () -> Athlete.<Athlete>find("stravaAthleteId", 424242L).firstResult());
        assertNotNull(a, "find-or-create persisted the Strava athlete");
        assertEquals("LOGIN_TOKEN", a.stravaAccessToken);
        assertEquals("Cal Back", a.name);
        assertEquals(((Number) session.get("athlete_id")).intValue(), a.id.intValue());
    }

    /** Extract the raw `session` cookie value from a Set-Cookie header — the value
     * runs from after `session=` up to the first `;` (attributes), preserving the
     * itsdangerous value's own `.`/`=` characters. */
    private static String sessionFromSetCookie(String setCookie) {
        if (setCookie == null || !setCookie.startsWith("session=")) {
            return null;
        }
        String v = setCookie.substring("session=".length());
        int semi = v.indexOf(';');
        return semi >= 0 ? v.substring(0, semi) : v;
    }
}
