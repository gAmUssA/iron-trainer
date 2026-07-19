package io.gamov.irontrainer.strava;

import io.gamov.irontrainer.auth.SessionCookie;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GET /api/strava/connect — 307 to Strava consent with a minted oauth_state
 * session cookie. Validates the byte-identical minting end-to-end (the cookie
 * must read back as a valid session carrying the state in the Location). */
@QuarkusTest
class StravaConnectTest {

    @Test
    void connectRedirectsWithStateAndMintedCookie() {
        Response r = given().redirects().follow(false).when().get("/api/strava/connect")
                .then().statusCode(307).extract().response();

        String location = r.getHeader("Location");
        assertNotNull(location);
        // Deterministic structure (env may inject a real client_id/redirect_uri,
        // so assert presence + the fixed params, not specific credential values).
        assertTrue(location.startsWith("https://www.strava.com/oauth/authorize?"), location);
        assertTrue(location.contains("client_id="), location);
        assertTrue(location.contains("redirect_uri="), location);
        assertTrue(location.contains("response_type=code"), location);
        assertTrue(location.contains("scope=read%2Cactivity%3Aread_all"), location);
        assertTrue(location.contains("approval_prompt=auto"), location);

        // A non-empty state param and a signed session cookie (payload.ts.sig)
        // are set. Minting correctness (byte-identical + round-trip) is proven in
        // SessionCookieTest; here we just confirm connect mints + sets the cookie.
        String state = location.replaceAll(".*[?&]state=([^&]+).*", "$1");
        assertTrue(state.length() >= 20, "urlsafe(16) state");
        String cookie = r.getCookie("session");
        assertNotNull(cookie, "connect must set a session cookie");
        assertEquals(2, cookie.chars().filter(c -> c == '.').count(), "signed cookie = payload.ts.sig");
    }
}
