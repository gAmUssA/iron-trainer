package io.gamov.irontrainer.auth;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Smoke tests for the Sign-in-with-Apple endpoint (bean 3e6w). A real end-to-end
 * sign-in needs a live Apple identity token (device/TestFlight), so here we only
 * pin the rejection paths — no network/JWKS fetch is needed for these (a missing
 * or non-JWT token fails before key selection).
 */
@QuarkusTest
class AppleResourceTest {

    @Test
    void missingTokenIsBadRequest() {
        given().contentType("application/json").body("{}")
                .when().post("/api/auth/apple")
                .then().statusCode(400);
    }

    @Test
    void malformedTokenIsUnauthorized() {
        given().contentType("application/json").body("{\"identityToken\":\"not-a-jwt\"}")
                .when().post("/api/auth/apple")
                .then().statusCode(401)
                .body(notNullValue());
    }
}
