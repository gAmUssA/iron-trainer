package io.gamov.irontrainer;

import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.auth.BearerAuthFilter;
import io.gamov.irontrainer.auth.DeviceToken;
import io.gamov.irontrainer.plan.PlannedWorkout;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/** Exports vertical: .itw contract (schema_version 1, embedded thresholds,
 * steps passthrough), bearer auth, and cross-tenant 404. */
@QuarkusTest
class ExportItwTest {

    int athleteId;
    int otherAthleteId;
    int workoutId;
    String token;
    String otherToken;

    @BeforeEach
    void seed() {
        token = "tok-" + java.util.UUID.randomUUID();
        otherToken = "tok-" + java.util.UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            Athlete a = new Athlete();
            a.name = "Exporter";
            a.ftp = 228.0;
            a.thresholdHr = 160;
            a.persist();
            athleteId = a.id;

            Athlete b = new Athlete();
            b.name = "Stranger";
            b.persist();
            otherAthleteId = b.id;

            DeviceToken t = new DeviceToken();
            t.athleteId = athleteId;
            t.name = "test-device";
            t.tokenHash = BearerAuthFilter.sha256(token);
            t.persist();

            DeviceToken t2 = new DeviceToken();
            t2.athleteId = otherAthleteId;
            t2.name = "other-device";
            t2.tokenHash = BearerAuthFilter.sha256(otherToken);
            t2.persist();

            PlannedWorkout w = new PlannedWorkout();
            w.athleteId = athleteId;
            w.date = "2026-07-20";
            w.sport = "Bike";
            w.title = "Bike intervals";
            w.durationS = 3600;
            w.structureJson = "[{\"type\":\"work\",\"duration_s\":1200," +
                    "\"target\":{\"type\":\"power\",\"low\":200,\"high\":220}}]";
            w.persist();
            workoutId = w.id;
        });
    }

    @Test
    void itwContractWithBearer() {
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/export/workout/" + workoutId + ".itw")
                .then().statusCode(200)
                .body("schema_version", equalTo(1))
                .body("generator", equalTo("iron-trainer"))
                .body("sport", equalTo("Bike"))
                .body("athlete.ftp", equalTo(228.0f))
                .body("athlete.threshold_hr", equalTo(160))
                .body("steps", hasSize(1))
                .body("steps[0].target.low", equalTo(200));
    }

    @Test
    void crossTenantIs404() {
        given().header("Authorization", "Bearer " + otherToken)
                .when().get("/api/export/workout/" + workoutId + ".itw")
                .then().statusCode(404);
    }
}
