package io.gamov.irontrainer.auth;

import io.gamov.irontrainer.athlete.Athlete;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** The %test session secret (see application.properties). */
    private static final String SECRET = "test-secret-key";

    private static String sessionCookie(Integer athleteId) {
        return "session=" + SessionCookie.sign(Map.of("athlete_id", athleteId), SECRET);
    }

    // Seed/read in COMMITTED transactions (not @Transactional on the method) so the
    // over-HTTP request sees the data and holds no lock against it — see SessionAuthTest.
    private static Integer seedAthlete(java.util.function.Consumer<Athlete> init) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Athlete a = new Athlete();
            init.accept(a);
            a.persist();
            return a.id;
        });
    }

    private static String appleIdOf(Integer athleteId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Athlete a = Athlete.findById(athleteId);
            return a.appleUserId;
        });
    }

    /** Happy path: a logged-in athlete with no Apple id links an unused one. */
    @Test
    void authenticatedLinkOntoUnusedAppleIdSucceeds() {
        Integer a1Id = seedAthlete(a -> a.stravaAthleteId = 2_002L);

        when(appleAuth.verify(anyString())).thenReturn(new AppleAuth.AppleId("apple-new"));

        given().contentType("application/json").header("Cookie", sessionCookie(a1Id))
                .body("{\"identityToken\":\"x\"}")
                .when().post("/api/auth/apple/web")
                .then().statusCode(200);

        assertEquals("apple-new", appleIdOf(a1Id), "Apple id should link onto the logged-in athlete");
    }

    /**
     * Regression (data loss): a logged-in athlete A1 must NOT be switched to a
     * different account when the Apple id is already bound to A2. The linking call
     * must 409 and leave both accounts untouched — never Set-Cookie A2.
     */
    @Test
    void authenticatedLinkCannotSwitchToAnAppleIdBoundElsewhere() {
        Integer a1Id = seedAthlete(a -> a.stravaAthleteId = 1_001L);
        Integer a2Id = seedAthlete(a -> a.appleUserId = "apple-A2");

        when(appleAuth.verify(anyString())).thenReturn(new AppleAuth.AppleId("apple-A2"));

        given().contentType("application/json").header("Cookie", sessionCookie(a1Id))
                .body("{\"identityToken\":\"x\"}")
                .when().post("/api/auth/apple/web")
                .then().statusCode(409);

        assertNull(appleIdOf(a1Id), "A1 must not be linked");
        assertEquals("apple-A2", appleIdOf(a2Id), "A2 must be untouched");
    }

    /** A logged-in athlete already linked to a DIFFERENT Apple id must 409, not fork. */
    @Test
    void authenticatedAthleteWithADifferentAppleIdConflicts() {
        Integer a1Id = seedAthlete(a -> a.appleUserId = "apple-existing");

        when(appleAuth.verify(anyString())).thenReturn(new AppleAuth.AppleId("apple-other"));

        given().contentType("application/json").header("Cookie", sessionCookie(a1Id))
                .body("{\"identityToken\":\"x\"}")
                .when().post("/api/auth/apple/web")
                .then().statusCode(409);

        assertEquals("apple-existing", appleIdOf(a1Id), "the existing Apple link must be unchanged");
    }
}
