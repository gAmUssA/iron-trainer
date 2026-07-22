package io.gamov.irontrainer.auth;

import io.gamov.irontrainer.athlete.Athlete;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Account-linking safety for Sign in with Apple (bean 3e6w). AppleAuth is mocked
 * so we exercise the account resolution without a live Apple token.
 */
@QuarkusTest
class AppleLinkingTest {

    @InjectMock
    AppleAuth appleAuth;

    /**
     * Regression: with auth-required off (the default), BearerAuthFilter falls the
     * current athlete back to the default id. An UNAUTHENTICATED Apple sign-in must
     * NOT link onto an existing athlete — it must create a fresh account. (The bug
     * this pins would hijack the owner account.)
     */
    @Test
    @Transactional
    void anonymousSignInDoesNotHijackAnExistingAthlete() {
        Athlete owner = new Athlete();
        owner.stravaAthleteId = 99_999L;
        owner.persistAndFlush();
        Integer ownerId = owner.id;

        when(appleAuth.verify(anyString())).thenReturn(new AppleAuth.AppleId("siwa-anon-sub"));

        given().contentType("application/json").body("{\"identityToken\":\"x\"}")
                .when().post("/api/auth/apple")      // no Authorization header
                .then().statusCode(200);

        // The pre-existing athlete is untouched…
        Athlete reloaded = Athlete.findById(ownerId);
        assertNull(reloaded.appleUserId, "existing athlete must not be linked to the Apple id");
        // …and the Apple id landed on a distinct, fresh athlete.
        Athlete appleAthlete = Athlete.find("appleUserId", "siwa-anon-sub").firstResult();
        assertNotNull(appleAthlete);
        assertNotEquals(ownerId, appleAthlete.id, "anonymous sign-in must create a new account");
    }
}
