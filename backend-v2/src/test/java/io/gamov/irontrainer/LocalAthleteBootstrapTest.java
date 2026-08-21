package io.gamov.irontrainer;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.gamov.irontrainer.athlete.Athlete;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A fresh self-host install must be able to WRITE, not just read (bean zvc2).
 *
 * <p>The original bug was invisible to every read-only check: {@code /api/status}
 * reported {@code authenticated:true} and GETs returned 200, while every write died on
 * a foreign key because {@code default-athlete-id} pointed at a row nothing created.
 * So the test that matters here asserts a WRITE reaches the database — the cheap
 * assertions are exactly the ones that missed it.
 */
@QuarkusTest
@TestProfile(LocalAthleteBootstrapTest.LocalMode.class)
class LocalAthleteBootstrapTest {

    static final int AID = 7301;

    /** Local self-host shape: auth off, every request is one fixed athlete. */
    public static class LocalMode implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "irontrainer.auth-required", "false",
                    "irontrainer.default-athlete-id", String.valueOf(AID));
        }
    }

    @Inject
    LocalAthleteBootstrap bootstrap;

    @Test
    void bootstrapCreatesTheAthleteTheAuthFilterHandsOut() {
        Athlete a = QuarkusTransaction.requiringNew().call(() -> Athlete.<Athlete>findById(AID));
        assertNotNull(a, "startup must create the athlete that default-athlete-id names");
    }

    @Test
    void aFreshInstallCanActuallyWrite() {
        // The regression. Before the fix this was a 500:
        //   insert or update on table "fitness_test_result" violates foreign key
        //   constraint ... Key (athlete_id)=(N) is not present in table "athlete".
        int id = given().contentType("application/json")
                .body("{\"test_slug\":\"bike-ftp-20\",\"date\":\"2026-01-05\","
                        + "\"inputs\":{\"avg_power_w\":240}}")
                .when().post("/api/tests/result")
                .then().statusCode(200)
                .body("result.ftp", is(228))
                .extract().path("id");

        // Readable back, proving it reached the database under the same athlete the
        // filter resolves — not merely that the POST returned 200.
        given().when().get("/api/tests/results")
                .then().statusCode(200)
                .body("results.find { it.id == " + id + " }.result.ftp", is(228));
    }

    @Test
    void runningAgainIsANoOp() {
        // Restarts and upgrades re-fire StartupEvent; a second run must not blow up on
        // the primary key or duplicate the athlete.
        long before = QuarkusTransaction.requiringNew().call(() -> Athlete.count("id = ?1", AID));
        bootstrap.onStart(new StartupEvent());
        long after = QuarkusTransaction.requiringNew().call(() -> Athlete.count("id = ?1", AID));
        assertEquals(before, after, "re-running the bootstrap must not create a second athlete");
    }

    @Test
    void theSequenceIsPastTheSeededIdSoNormalInsertsStillWork() {
        // An explicit-id insert leaves the serial's sequence behind. If it is not
        // pushed forward, the next ordinary insert (connecting Strava, say) reuses the
        // id and dies on the primary key — a bug that would only appear later, in
        // someone else's session.
        Integer fresh = QuarkusTransaction.requiringNew().call(() -> {
            Athlete a = new Athlete();
            a.name = "Second";
            a.persist();
            return a.id;
        });
        assertNotNull(fresh);
        org.junit.jupiter.api.Assertions.assertNotEquals(AID, fresh.intValue(),
                "a normal insert must not collide with the explicitly seeded id");
        QuarkusTransaction.requiringNew().run(() -> Athlete.delete("id = ?1", fresh));
    }
}
