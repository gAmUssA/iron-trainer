package io.gamov.irontrainer.metrics;

import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.auth.BearerAuthFilter;
import io.gamov.irontrainer.auth.DeviceToken;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/** PMC windowing: default 180, days=0 = all, days=N inclusive-of-today,
 * response shape, bearer auth. Mirrors the intent of the Python
 * test_chart_windows + the contract test_pmc_windowing. */
@QuarkusTest
class PmcResourceTest {

    String token;

    @BeforeEach
    void seed() {
        token = "tok-" + java.util.UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            Athlete a = new Athlete();
            a.name = "PMC";
            a.persist();
            DeviceToken t = new DeviceToken();
            t.athleteId = a.id;
            t.name = "d";
            t.tokenHash = BearerAuthFilter.sha256(token);
            t.persist();
            // 400 days ending today.
            for (int ago = 399; ago >= 0; ago--) {
                MetricDaily m = new MetricDaily();
                m.athleteId = a.id;
                m.date = LocalDate.now().minusDays(ago).toString();
                m.tss = 50.0; m.ctl = 60.0; m.atl = 55.0; m.tsb = 5.0;
                m.persist();
            }
        });
    }

    @Test
    void defaultWindowIs180() {
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/metrics/pmc")
                .then().statusCode(200)
                .body("window_days", equalTo(180))
                .body("total_days", equalTo(400))
                .body("days", hasSize(180))
                .body("days[0].date", equalTo(LocalDate.now().minusDays(179).toString()))
                .body("days[179].date", equalTo(LocalDate.now().toString()))
                .body("days[0].athlete_id", org.hamcrest.Matchers.notNullValue());
    }

    @Test
    void zeroMeansAll() {
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/metrics/pmc?days=0")
                .then().statusCode(200).body("days", hasSize(400));
    }

    @Test
    void narrowWindowClamps() {
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/metrics/pmc?days=30")
                .then().statusCode(200)
                .body("window_days", equalTo(30))
                .body("days", hasSize(lessThanOrEqualTo(30)));
    }

    @Test
    void negativeRejected() {
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/metrics/pmc?days=-5").then().statusCode(422);
    }
}
