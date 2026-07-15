package io.gamov.irontrainer;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

@QuarkusTest
class ProbeResourceTest {

    @Test
    void restPanacheFlywayRoundTrip() {
        given().when().post("/api/v2/probe").then().statusCode(200);
        given().when().get("/api/v2/probe").then().statusCode(200)
                .body("backend", equalTo("v2"))
                .body("athletes", greaterThanOrEqualTo(1));
    }
}
