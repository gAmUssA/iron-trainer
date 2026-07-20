package io.gamov.irontrainer.auth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.gamov.irontrainer.athlete.Athlete;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Device pairing — the native-app bearer-token minting. */
@QuarkusTest
@TestProfile(DevicePairingTest.Profile.class)
class DevicePairingTest {

    static final int AID = 9001;

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("irontrainer.default-athlete-id", String.valueOf(AID));
        }
    }

    @BeforeEach
    void seedAthlete() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (Athlete.findById(AID) == null) {
                Athlete.getEntityManager()
                        .createNativeQuery("INSERT INTO athlete (id) VALUES (" + AID + ")").executeUpdate();
            }
        });
    }

    @Test
    void pairThenClaimMintsAStoredToken() {
        Response pc = given().contentType("application/json").body("{\"name\":\"my phone\"}")
                .when().post("/api/device/pairing-code")
                .then().statusCode(200)
                .body("code", notNullValue())
                .body("expires_in", is(600))
                .body("expires_at", notNullValue())
                .extract().response();
        String code = pc.jsonPath().getString("code");

        Response claim = given().contentType("application/json")
                .body("{\"code\":\"" + code + "\",\"device_name\":\"watch\"}")
                .when().post("/api/device/claim")
                .then().statusCode(200)
                .body("token", notNullValue())
                .body("athlete", notNullValue())
                .extract().response();
        String token = claim.jsonPath().getString("token");

        // The minted token is stored (hashed) and resolvable — proves BearerAuthFilter
        // would authenticate it.
        String name = QuarkusTransaction.requiringNew().call(() -> {
            DeviceToken row = DeviceToken.find("tokenHash", BearerAuthFilter.sha256(token)).firstResult();
            return row == null ? null : row.name;
        });
        assertNotNull(name, "claimed token is stored");
        // device_name overrode the pairing name.
        org.junit.jupiter.api.Assertions.assertEquals("watch", name);
    }

    @Test
    void invalidCodeIs400AndMissingCodeIs422() {
        given().contentType("application/json").body("{\"code\":\"deadbeef\"}")
                .when().post("/api/device/claim").then().statusCode(400);
        given().contentType("application/json").body("{}")
                .when().post("/api/device/claim").then().statusCode(422);   // code required
    }

    @Test
    void revokeDeletesTokens() {
        // Mint one, then revoke — count is >= 1.
        given().contentType("application/json").body("{}").post("/api/device/pairing-code");
        given().when().delete("/api/device/tokens")
                .then().statusCode(200).body("revoked", greaterThanOrEqualTo(1));
    }

    @Test
    void ingestTokenMintsAndCannotMintSiblings() {
        Response r = given().when().post("/api/device/ingest-token")
                .then().statusCode(200)
                .body("token", notNullValue())
                .body("path", is("/api/health/ingest"))
                .extract().response();
        String ingest = r.jsonPath().getString("token");
        // An ingest token cannot mint new tokens → 403.
        given().header("Authorization", "Bearer " + ingest)
                .when().post("/api/device/ingest-token").then().statusCode(403);
    }

    @Test
    void claimThrottlesAfterTooManyFailures() {
        // Isolate this client via a fixed XFF so other tests don't perturb the count.
        String xff = "203.0.113.7";
        for (int i = 0; i < 10; i++) {
            given().header("X-Forwarded-For", xff).contentType("application/json")
                    .body("{\"code\":\"badcode0\"}")
                    .when().post("/api/device/claim").then().statusCode(400);
        }
        given().header("X-Forwarded-For", xff).contentType("application/json")
                .body("{\"code\":\"badcode0\"}")
                .when().post("/api/device/claim").then().statusCode(429);
    }
}
