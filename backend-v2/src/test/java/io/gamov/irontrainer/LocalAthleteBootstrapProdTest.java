package io.gamov.irontrainer;

import static org.junit.jupiter.api.Assertions.assertNull;

import io.gamov.irontrainer.athlete.Athlete;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The bootstrap must NEVER run on the SaaS deployment (bean zvc2).
 *
 * <p>There, athlete rows are owned by the auth flow — Strava OAuth, Apple sign-in,
 * device pairing. Conjuring an unowned athlete row into the production database would
 * be a data-integrity problem, not a convenience, so the guard gets its own test
 * rather than resting on "auth-required is true in prod anyway".
 */
@QuarkusTest
@TestProfile(LocalAthleteBootstrapProdTest.AuthRequired.class)
class LocalAthleteBootstrapProdTest {

    static final int AID = 7302;

    /** SaaS shape: auth on, session secret present (StartupBanner refuses to boot
     * without it), and a default-athlete-id that must be ignored. */
    public static class AuthRequired implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "irontrainer.auth-required", "true",
                    "irontrainer.session-secret", "test-secret",
                    "irontrainer.default-athlete-id", String.valueOf(AID));
        }
    }

    @Test
    void noAthleteIsCreatedWhenAuthIsRequired() {
        Athlete a = QuarkusTransaction.requiringNew().call(() -> Athlete.<Athlete>findById(AID));
        assertNull(a, "the bootstrap must not create athletes on an authenticated deployment");
    }
}
