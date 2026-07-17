package io.gamov.irontrainer.strava;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.athlete.Athlete;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end sync against a WireMock Strava: token refresh → fetch → map +
 * upsert → dedup → rebuild PMC. Exercises the real Strava REST client over HTTP
 * plus the Dev Services Postgres write path. */
@QuarkusTest
@QuarkusTestResource(WireMockStrava.class)
class StravaSyncTest {

    @Inject
    StravaSync sync;

    @Test
    void syncRefreshesTokenUpsertsMapsAndRebuilds() {
        // A connected athlete with an EXPIRED token (expires_at 0) → forces refresh.
        int aid = QuarkusTransaction.requiringNew().call(() -> {
            Athlete a = new Athlete();
            a.ftp = 250.0;
            a.thresholdPaceRun = 300.0;
            a.stravaRefreshToken = "OLD_REFRESH";
            a.stravaTokenExpiresAt = 0L;
            a.persist();
            return a.id;
        });

        Map<String, Object> r = sync.runSync(aid, true);

        assertEquals(2, r.get("fetched"));
        assertEquals(2, r.get("upserted"));
        assertEquals(2, r.get("total_activities"));
        assertTrue((int) r.get("metrics_days") > 0, "PMC rebuilt from the synced activities");

        QuarkusTransaction.requiringNew().run(() -> {
            Activity ride = Activity.findById(111L);
            assertNotNull(ride, "the Ride was upserted");
            assertEquals("Bike", ride.sport);          // normalize_sport("Ride")
            assertEquals(aid, ride.athleteId);
            assertEquals(1, ride.hasPowerMeter);        // device_watts → power meter
            assertNotNull(ride.tss);
            assertTrue(ride.tss > 0);                   // TSS computed at map time

            // The expired token was refreshed via WireMock and persisted.
            Athlete a = Athlete.findById(aid);
            assertEquals("NEW_TOKEN", a.stravaAccessToken);
            assertEquals("REFRESH2", a.stravaRefreshToken);
            // A refresh_token grant carries no athlete object, so identity is
            // untouched (it's set only on the initial OAuth connect — bean xtre).
            assertNull(a.stravaAthleteId);
        });
    }
}
