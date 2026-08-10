package io.gamov.irontrainer.admin;

import static io.restassured.RestAssured.given;

import io.gamov.irontrainer.athlete.Athlete;
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

    static void job(int athleteId, String kind, String status, String createdAt) {
        Job j = new Job();
        j.athleteId = athleteId; // job.athlete_id is NOT NULL
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
            Athlete a = new Athlete();
            a.name = "HealthTest";
            a.persist();
            int aid = a.id;
            for (int i = 0; i < 3; i++) job(aid, sync, "succeeded", now);
            for (int i = 0; i < 2; i++) job(aid, sync, "failed", now);
            job(aid, old, "failed", ancient); // outside the 7-day window → must be excluded
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

    @Test
    void durationPercentilesPerKind() {
        // Four succeeded jobs of one kind with 1/2/3/4s spans → p50=2000ms, p95=4000ms.
        String kind = "htdur_" + UUID.randomUUID().toString().substring(0, 8);
        String now = PyJson.utcNowIso();
        java.time.OffsetDateTime base = java.time.OffsetDateTime.parse(now);
        QuarkusTransaction.requiringNew().run(() -> {
            Athlete a = new Athlete();
            a.name = "DurTest";
            a.persist();
            for (int s : new int[] {1, 2, 3, 4}) {
                Job j = new Job();
                j.athleteId = a.id;
                j.kind = kind;
                j.status = "succeeded";
                j.createdAt = now;                         // in-window
                j.startedAt = base.toString();
                j.finishedAt = base.plusSeconds(s).toString();
                j.persist();
            }
        });

        Map<String, Object> k = given().header("Cookie", adminCookie())
                .when().get("/api/admin/health/jobs?days=7")
                .then().statusCode(200)
                .extract().jsonPath().getMap("kinds.find { it.kind == '" + kind + "' }");
        assert k != null : "duration kind missing";
        assert ((Number) k.get("timed")).intValue() == 4 : "timed should be 4, got " + k.get("timed");
        assert ((Number) k.get("p50_ms")).longValue() == 2000L : "p50 should be 2000, got " + k.get("p50_ms");
        assert ((Number) k.get("p95_ms")).longValue() == 4000L : "p95 should be 4000, got " + k.get("p95_ms");
    }
}
