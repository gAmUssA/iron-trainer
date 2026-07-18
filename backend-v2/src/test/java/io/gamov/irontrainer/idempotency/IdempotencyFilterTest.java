package io.gamov.irontrainer.idempotency;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** Idempotency-Key on a real write (POST /api/v2/athletes creates a row): a retry
 * with the same key replays the first response and does NOT create a second row;
 * a different key (or none) runs the mutation normally. */
@QuarkusTest
class IdempotencyFilterTest {

    private int athleteCount() {
        return given().when().get("/api/v2/athletes").then().statusCode(200)
                .extract().path("athletes");
    }

    @Test
    void sameKeyReplaysAndDoesNotReapply() {
        int before = athleteCount();

        // First write with a key → creates one athlete.
        given().header("Idempotency-Key", "key-abc-1")
                .when().post("/api/v2/athletes").then().statusCode(200);
        int afterFirst = athleteCount();
        org.junit.jupiter.api.Assertions.assertEquals(before + 1, afterFirst);

        // Retry with the SAME key → replayed, no second athlete.
        given().header("Idempotency-Key", "key-abc-1")
                .when().post("/api/v2/athletes").then()
                .statusCode(200)
                .header("Idempotency-Replayed", "true");
        org.junit.jupiter.api.Assertions.assertEquals(afterFirst, athleteCount());
    }

    @Test
    void differentKeyRunsAgain() {
        int before = athleteCount();
        given().header("Idempotency-Key", "key-diff-A")
                .when().post("/api/v2/athletes").then().statusCode(200)
                .header("Idempotency-Replayed", org.hamcrest.Matchers.nullValue());
        given().header("Idempotency-Key", "key-diff-B")
                .when().post("/api/v2/athletes").then().statusCode(200);
        org.junit.jupiter.api.Assertions.assertEquals(before + 2, athleteCount());
    }

    @Test
    void noKeyIsNormalWrite() {
        int before = athleteCount();
        given().when().post("/api/v2/athletes").then().statusCode(200)
                .header("Idempotency-Replayed", org.hamcrest.Matchers.nullValue());
        org.junit.jupiter.api.Assertions.assertEquals(before + 1, athleteCount());
    }

    @Test
    void syncRegenerateReplays() {
        // A key on the SYNC LLM regenerate endpoint (deterministic fallback in
        // test): the second call replays the first response rather than re-running.
        given().when().post("/api/v2/athletes").then().statusCode(200);
        given().header("Idempotency-Key", "regen-key-1")
                .when().post("/api/nutrition/race-day/regenerate").then().statusCode(200);
        given().header("Idempotency-Key", "regen-key-1")
                .when().post("/api/nutrition/race-day/regenerate").then()
                .statusCode(200)
                .header("Idempotency-Replayed", "true")
                .body("llm_used", equalTo(false));
    }

    @Test
    void sameKeyDifferentEndpointDoesNotCrossReplay() {
        // A key cached on one endpoint must NOT replay on a different endpoint
        // (key is scoped by method+path) — the second endpoint runs normally.
        given().when().post("/api/v2/athletes").then().statusCode(200);
        given().header("Idempotency-Key", "shared-key-x")
                .when().post("/api/v2/athletes").then().statusCode(200);
        // Same key, different path → runs the regenerate (returns a plan), not a replay.
        given().header("Idempotency-Key", "shared-key-x")
                .when().post("/api/nutrition/race-day/regenerate").then()
                .statusCode(200)
                .header("Idempotency-Replayed", org.hamcrest.Matchers.nullValue())
                .body("llm_used", equalTo(false));
    }

    @Test
    void asyncWriteIsNotIdempotencyCached() {
        // Async writes are deduped by the job system, not the idempotency cache —
        // a keyed async submit must NOT be replayed by this filter.
        given().when().post("/api/v2/athletes").then().statusCode(200);
        given().header("Idempotency-Key", "async-key-1")
                .when().post("/api/nutrition/race-day/regenerate?async=1").then().statusCode(200);
        given().header("Idempotency-Key", "async-key-1")
                .when().post("/api/nutrition/race-day/regenerate?async=1").then()
                .statusCode(200)
                .header("Idempotency-Replayed", org.hamcrest.Matchers.nullValue());
    }
}
