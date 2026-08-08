package io.gamov.irontrainer.admin;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** Admin console auth + jobs endpoint (bean gfb3). Test profile sets
 * irontrainer.admin-password=test-admin-pw + a fixed session secret. */
@QuarkusTest
class AdminResourceTest {

    @Test
    void loginWrongPasswordIsUnauthorized() {
        given().contentType("application/json").body("{\"password\":\"wrong\"}")
                .when().post("/api/admin/login")
                .then().statusCode(401);
    }

    @Test
    void jobsRequiresAdminSession() {
        given().when().get("/api/admin/jobs").then().statusCode(401);
    }

    @Test
    void loginThenJobsWithCookieSucceeds() {
        String setCookie = given().contentType("application/json").body("{\"password\":\"test-admin-pw\"}")
                .when().post("/api/admin/login")
                .then().statusCode(200)
                .extract().header("Set-Cookie");

        // "admin_session=<value>; path=/; ..." → send just the name=value pair back.
        String cookie = setCookie.split(";", 2)[0];

        given().header("Cookie", cookie)
                .when().get("/api/admin/jobs")
                .then().statusCode(200)
                .body("jobs", notNullValue())
                .body("total", notNullValue());
    }
}
