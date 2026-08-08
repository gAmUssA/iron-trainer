package io.gamov.irontrainer.admin;

import static io.restassured.RestAssured.given;

import io.gamov.irontrainer.jobs.Job;
import io.gamov.irontrainer.util.PyJson;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Admin sync-health telemetry (bean j41l): guard, per-kind aggregates, window
 * filtering, and the recent-failures feed. */
@QuarkusTest
class AdminHealthResourceTest {

    static void job(String kind, String status, String createdAt) {
        Job j = new Job();
        j.kind = kind;
        j.status = status;
        j.createdAt = createdAt;
        j.finishedAt = createdAt;
        if ("failed".equals(status)) j.error = "boom for " + kind;
        j.persist();
    }

    static String adminCookie() {
        String setCookie = given().contentType("application/json").body("{\"password\":\"test-admin-pw\"}")
                .when().post("/api/admin/login").then().statusCode(200)
                .extract().header("Set-Cookie");
        return setCookie.split(";", 2)[0];
    }

    @Test
    void healthRequiresAdminSession() {
        given().when().get("/api/admin/health/jobs").then().statusCode(401);
    }

    @Test
    void aggregatesWindowAndRecentFailures() {
        // Unique kinds so assertions are deterministic regardless of other test data.
        String sync = "htsync_" + UUID.randomUUID().toString().substring(0, 8);
        String old = "htold_" + UUID.randomUUID().toString().substring(0, 8);
        String now = PyJson.utcNowIso();
        String ancient = "2020-01-01T00:00:00.000000+00:00";

        QuarkusTransaction.requiringNew().run(() -> {
            for (int i = 0; i < 3; i++) job(sync, "succeeded", now);
            for (int i = 0; i < 2; i++) job(sync, "failed", now);
            job(old, "failed", ancient); // outside the 7-day window → must be excluded
        });

        JsonPath jp = given().header("Cookie", adminCookie())
                .when().get("/api/admin/health/jobs?days=7")
                .then().statusCode(200)
                .extract().jsonPath();

        assert jp.getInt("window_days") == 7;

        Map<String, Object> k = jp.getMap("kinds.find { it.kind == '" + sync + "' }");
        assert k != null : "sync kind missing from aggregates";
        assert ((Number) k.get("total")).intValue() == 5 : "total should be 5, got " + k.get("total");
        assert ((Number) k.get("succeeded")).intValue() == 3;
        assert ((Number) k.get("failed")).intValue() == 2;
        assert Math.abs(((Number) k.get("failure_rate")).doubleValue() - 0.4) < 1e-6
                : "failure_rate should be 0.4, got " + k.get("failure_rate");

        // The ancient-dated kind is outside the window → absent from both sections.
        assert jp.getMap("kinds.find { it.kind == '" + old + "' }") == null
                : "out-of-window kind leaked into aggregates";

        List<Map<String, Object>> failures = jp.getList("recent_failures");
        assert failures.stream().anyMatch(f -> sync.equals(f.get("kind")))
                : "recent failures should include the in-window failed kind";
        assert failures.stream().noneMatch(f -> old.equals(f.get("kind")))
                : "recent failures should exclude the out-of-window kind";
    }
}
