package io.gamov.irontrainer.athlete;

import io.gamov.irontrainer.activity.Activity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** seed_profile_if_empty over the Dev Services Postgres: infers thresholds from
 * recent activity when the profile is empty, re-costs activities, and no-ops once
 * any threshold is set. Wired into the Strava sync (StravaSync.persist). */
@QuarkusTest
class SeedProfileTest {

    private static Activity ride(int aid, long id, String sport, int daysAgo, int moving,
            double distance, Double weightedPower, Double avgHr, Double maxHr) {
        Activity a = new Activity();
        a.id = id;
        a.athleteId = aid;
        a.sport = sport;
        a.startDate = LocalDate.now().minusDays(daysAgo) + "T08:00:00Z";
        a.movingTime = moving;
        a.distance = distance;
        a.weightedPower = weightedPower;
        a.avgHr = avgHr;
        a.maxHr = maxHr;
        a.isDuplicate = 0;
        return a;
    }

    @Test
    void seedsEmptyProfileAndReCostsActivities() {
        int aid = QuarkusTransaction.requiringNew().call(() -> {
            Athlete a = new Athlete();
            a.persist();                                     // no thresholds set
            int id = a.id;
            ride(id, 950001L, "Bike", 5, 3600, 40000, 270.0, 155.0, 175.0).persist();
            ride(id, 950002L, "Run", 10, 3000, 10000, null, 162.0, 182.0).persist();
            ride(id, 950003L, "Swim", 15, 1800, 2000, null, null, null).persist();
            return id;
        });

        Map<String, Object> seeded = QuarkusTransaction.requiringNew()
                .call(() -> Analysis.seedProfileIfEmpty(aid, LocalDate.now()));

        assertNotNull(seeded, "an empty profile is seeded");
        assertNotNull(seeded.get("ftp"));
        assertNotNull(seeded.get("threshold_pace_run"));
        assertNotNull(seeded.get("css_swim"));

        QuarkusTransaction.requiringNew().run(() -> {
            Athlete a = Athlete.findById(aid);
            assertNotNull(a.ftp, "ftp persisted onto the athlete");
            assertNotNull(a.thresholdPaceRun);
            assertNotNull(a.cssSwim);
            assertNotNull(a.updatedAt, "updated_at bumped");
            // recompute_tss ran: the bike ride now carries a TSS from the inferred FTP.
            Activity bike = Activity.findById(950001L);
            assertNotNull(bike.tss, "activities re-costed after seeding");
            assertTrue(bike.tss > 0);
        });
    }

    @Test
    void noOpWhenProfileAlreadyHasThresholds() {
        int aid = QuarkusTransaction.requiringNew().call(() -> {
            Athlete a = new Athlete();
            a.ftp = 300.0;                                   // already has a threshold
            a.persist();
            int id = a.id;
            ride(id, 950101L, "Bike", 5, 3600, 40000, 270.0, 155.0, 175.0).persist();
            return id;
        });

        Map<String, Object> seeded = QuarkusTransaction.requiringNew()
                .call(() -> Analysis.seedProfileIfEmpty(aid, LocalDate.now()));

        assertNull(seeded, "no-op when any threshold is already set");
        QuarkusTransaction.requiringNew().run(() -> {
            Athlete a = Athlete.findById(aid);
            assertTrue(a.ftp == 300.0, "existing ftp untouched");
            assertNull(a.cssSwim, "inference did not run");
        });
    }
}
