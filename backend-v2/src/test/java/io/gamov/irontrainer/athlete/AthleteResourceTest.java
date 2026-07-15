package io.gamov.irontrainer.athlete;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/** Proves the REAL prod schema baseline: Flyway executes the cleaned pg_dump
 * on Dev Services Postgres, and Panache maps the actual athlete table. */
@QuarkusTest
class AthleteResourceTest {

    @Test
    void realSchemaRoundTrip() {
        given().when().post("/api/v2/athletes").then().statusCode(200);
        given().when().get("/api/v2/athletes").then().statusCode(200)
                .body("backend", equalTo("v2"))
                .body("athletes", greaterThanOrEqualTo(1));
    }
}
