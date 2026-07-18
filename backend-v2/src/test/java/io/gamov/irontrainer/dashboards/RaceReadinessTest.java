package io.gamov.irontrainer.dashboards;

import static io.restassured.RestAssured.given;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.auth.BearerAuthFilter;
import io.gamov.irontrainer.auth.DeviceToken;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.LocalDate;
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
}
