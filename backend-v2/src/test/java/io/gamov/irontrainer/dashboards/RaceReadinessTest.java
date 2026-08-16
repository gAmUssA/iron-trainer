package io.gamov.irontrainer.dashboards;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.auth.BearerAuthFilter;
import io.gamov.irontrainer.auth.DeviceToken;
import io.gamov.irontrainer.metrics.Metrics.Thresholds;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Smoke test: /api/metrics/readiness projects splits without a 500. */
@QuarkusTest
class RaceReadinessTest {

    @Test
    void projectsSplits() {
        String token = "rr-" + java.util.UUID.randomUUID();
        LocalDate today = LocalDate.now();
        QuarkusTransaction.requiringNew().run(() -> {
            Athlete a = new Athlete();
            a.name = "RR";
            a.cssSwim = 95.0;
            a.thresholdPaceRun = 300.0;
            a.raceDistance = "70.3";
            a.raceDate = "2026-09-26";
            a.cutoffSwimS = 4200;
            a.cutoffBikeS = 19800;
            a.cutoffFinishS = 30600;
            a.persist();
            DeviceToken t = new DeviceToken();
            t.athleteId = a.id;
            t.name = "d";
            t.tokenHash = BearerAuthFilter.sha256(token);
            t.persist();
            Activity ride = new Activity();
            ride.id = 990401L;
            ride.athleteId = a.id;
            ride.sport = "Bike";
            ride.startDate = today.minusDays(10) + "T08:00:00";
            ride.movingTime = 7200;
            ride.distance = 60000.0;
            ride.avgSpeed = 8.33;
            ride.isDuplicate = 0;
            ride.persist();
        });

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/metrics/readiness")
                .then().statusCode(200)
                .body("legs.swim.seconds", org.hamcrest.Matchers.notNullValue())
                .body("missing", org.hamcrest.Matchers.empty());
    }

    @Test
    void emptyCutoffsFallsBackToDefaults() {
        // Python `cutoffs or {default}`: an empty map is falsy → defaults, not an
        // NPE. (Latent — no route passes an empty map, but the parity contract.)
        Thresholds th = new Thresholds(null, null, null, null, 95.0);  // css_swim only
        Map<String, Object> out = RaceReadiness.raceReadiness(
                List.of(), th, null, new HashMap<>(), "70.3");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cutoffs = (List<Map<String, Object>>) out.get("cutoffs");
        assertEquals(4200, cutoffs.get(0).get("limit_s"));   // Swim default 70*60
    }

    // ── FTP intensity correction on the bike leg (bean m4vq) ──────────────────

    /** A qualifying long ride: >= 1 h, inside the 84-day window, with speed. */
    private static Activity ride(double avgSpeed, Double power) {
        Activity a = new Activity();
        a.sport = "Bike";
        a.startDate = LocalDate.now().minusDays(10) + "T08:00:00";
        a.movingTime = 7200;
        a.avgSpeed = avgSpeed;
        a.weightedPower = power;
        return a;
    }

    private static Thresholds ftp(Double w) {
        return new Thresholds(w, null, null, null, null);
    }

    @Test
    void noFtpKeepsTheRawObservedMean() {
        RaceReadiness.BikeSpeed s = RaceReadiness.recentBikeSpeed(
                List.of(ride(8.0, 160.0), ride(6.0, null)), ftp(null), "70.3");
        assertEquals(7.0, s.speedMs(), 1e-9);          // plain mean of both
        assertEquals("measured_speed", s.basis());
    }

    @Test
    void ftpScalesObservedSpeedByTheCubeRootOfThePowerRatio() {
        // race power = 0.78 x 250 = 195 W against an observed 160 W.
        RaceReadiness.BikeSpeed s = RaceReadiness.recentBikeSpeed(
                List.of(ride(8.0, 160.0)), ftp(250.0), "70.3");
        assertEquals(8.0 * Math.cbrt(195.0 / 160.0), s.speedMs(), 1e-9);
        assertEquals("measured_speed_ftp_scaled", s.basis());
    }

    @Test
    void ridesRiddenHarderThanRacePaceScaleDown() {
        // Observed 220 W > race 195 W — the projection must slow down, not speed
        // up. Guards against an abs()/inverted-ratio slip.
        RaceReadiness.BikeSpeed s = RaceReadiness.recentBikeSpeed(
                List.of(ride(8.0, 220.0)), ftp(250.0), "70.3");
        assertEquals(8.0 * Math.cbrt(195.0 / 220.0), s.speedMs(), 1e-9);
        org.junit.jupiter.api.Assertions.assertTrue(s.speedMs() < 8.0);
    }

    @Test
    void powerlessRidesAreExcludedFromTheCorrection() {
        // The 6.0 m/s ride has no power, so pairing its speed with the 160 W mean
        // would invent a data point. Correction must come from the 8.0 ride alone.
        RaceReadiness.BikeSpeed s = RaceReadiness.recentBikeSpeed(
                List.of(ride(8.0, 160.0), ride(6.0, null)), ftp(250.0), "70.3");
        assertEquals(8.0 * Math.cbrt(195.0 / 160.0), s.speedMs(), 1e-9);
    }

    @Test
    void fullDistanceUsesTheLowerRaceIntensity() {
        // 140.6 is ridden at 0.70 x FTP, not 0.78.
        RaceReadiness.BikeSpeed s = RaceReadiness.recentBikeSpeed(
                List.of(ride(8.0, 160.0)), ftp(250.0), "140.6");
        assertEquals(8.0 * Math.cbrt(175.0 / 160.0), s.speedMs(), 1e-9);
    }

    @Test
    void absurdRatiosAreClampedNotBelieved() {
        // 0.78 x 400 = 312 W against 100 W observed → cbrt 1.46, clamped to 1.25.
        RaceReadiness.BikeSpeed s = RaceReadiness.recentBikeSpeed(
                List.of(ride(8.0, 100.0)), ftp(400.0), "70.3");
        assertEquals(10.0, s.speedMs(), 1e-9);
    }

    @Test
    void noQualifyingRidesIsNull() {
        Activity shortRide = ride(8.0, 200.0);
        shortRide.movingTime = 1800;                    // under the 1 h floor
        org.junit.jupiter.api.Assertions.assertNull(
                RaceReadiness.recentBikeSpeed(List.of(shortRide), ftp(250.0), "70.3"));
        org.junit.jupiter.api.Assertions.assertNull(
                RaceReadiness.recentBikeSpeed(List.of(), ftp(250.0), "70.3"));
    }

    @Test
    void bikeLegReportsItsBasis() {
        Map<String, Object> out = RaceReadiness.raceReadiness(
                List.of(ride(8.0, 160.0)), ftp(250.0), null, new HashMap<>(), "70.3");
        @SuppressWarnings("unchecked")
        Map<String, Object> legs = (Map<String, Object>) out.get("legs");
        @SuppressWarnings("unchecked")
        Map<String, Object> bike = (Map<String, Object>) legs.get("bike");
        assertEquals("measured_speed_ftp_scaled", bike.get("basis"));
        org.junit.jupiter.api.Assertions.assertTrue(
                ((String) out.get("note")).contains("78% of FTP"));
    }
}
