package io.gamov.irontrainer.whoop;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.gamov.irontrainer.athlete.Athlete;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** POST /api/whoop/import (multipart ZIP → per-day upsert) + GET /api/whoop/cycles.
 * Dedicated default athlete (7001) — same isolation trick as StravaImportEndpointTest. */
@QuarkusTest
@TestProfile(WhoopResourceTest.Profile.class)
class WhoopResourceTest {

    static final int AID = 7001;

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // WHOOP credentials are pinned EMPTY, not left to config. They default
            // to ${WHOOP_CLIENT_ID:} — so on a developer machine that has real ones
            // exported, /status reports configured=true and any assertion about it
            // passes locally and fails in CI. Pinning makes the test mean the same
            // thing everywhere, and models the self-host install that has no
            // credentials at all.
            return Map.of("irontrainer.default-athlete-id", String.valueOf(AID),
                    "whoop.client-id", "",
                    "whoop.client-secret", "");
        }
    }

    @BeforeEach
    void seedDefaultAthlete() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (Athlete.findById(AID) == null) {
                Athlete.getEntityManager()
                        .createNativeQuery("INSERT INTO athlete (id) VALUES (" + AID + ")")
                        .executeUpdate();
            }
        });
    }

    private static final String HEADER =
            "Cycle start time,Cycle end time,Cycle timezone,Recovery score %,"
                    + "Resting heart rate (bpm),Heart rate variability (ms),Skin temp (celsius),"
                    + "Blood oxygen %,Day Strain,Energy burned (cal),Max HR (bpm),Average HR (bpm),"
                    + "Sleep onset,Wake onset,Sleep performance %,Respiratory rate (rpm),"
                    + "Asleep duration (min),In bed duration (min),Sleep efficiency %";

    @Test
    void importsThenServesCyclesAndReimportIsIdempotent() throws Exception {
        String csv = String.join("\n",
                HEADER,
                "2026-07-27 02:10:00,2026-07-28 01:58:00,UTC-04:00,45,54,38.0,34.1,95,11.0,2100,"
                        + "165,85,2026-07-27 02:20:00,2026-07-27 10:30:00,75,15.8,390,430,88",
                "2026-07-28 02:14:30,2026-07-29 01:58:00,UTC-04:00,67,52,48.5,33.9,96,14.2,2456,"
                        + "171,88,2026-07-28 02:20:00,2026-07-28 10:41:12,89,16.1,432,470,92") + "\n";

        given().multiPart("file", "whoop.zip", zipOf("physiological_cycles.csv", csv), "application/zip")
                .when().post("/api/whoop/import")
                .then().statusCode(200)
                .body("cycles", is(2))
                .body("first_date", is("2026-07-27"))
                .body("last_date", is("2026-07-28"));

        given().when().get("/api/whoop/cycles?days=30")
                .then().statusCode(200)
                .body("days.size()", is(2))
                // Newest first, like /api/health/recovery.
                .body("days[0].date", is("2026-07-28"))
                .body("days[0].recovery_score", is(67.0f))
                .body("days[0].hrv_rmssd_ms", is(48.5f))
                .body("days[0].day_strain", is(14.2f))
                .body("days[0].asleep_h", is(7.2f))
                .body("days[1].date", is("2026-07-27"));

        // Re-upload with a corrected value for the same day → update, not duplicate.
        String csv2 = HEADER + "\n"
                + "2026-07-28 02:14:30,2026-07-29 01:58:00,UTC-04:00,70,52,48.5,33.9,96,14.2,2456,"
                + "171,88,2026-07-28 02:20:00,2026-07-28 10:41:12,89,16.1,432,470,92\n";
        given().multiPart("file", "whoop.zip", zipOf("physiological_cycles.csv", csv2), "application/zip")
                .when().post("/api/whoop/import")
                .then().statusCode(200)
                .body("cycles", is(1));

        given().when().get("/api/whoop/cycles?days=30")
                .then().statusCode(200)
                .body("days.size()", is(2))
                .body("days[0].recovery_score", is(70.0f));
    }

    @Test
    void analyzeIsRateLimitedPerDay() {
        // Seed today's runs at the cap; the gate fires before the LLM check, so
        // this tests 429 even though tests run keyless (which would 503).
        String today = java.time.Instant.now().toString().substring(0, 10);
        QuarkusTransaction.requiringNew().run(() -> {
            WhoopInsight row = WhoopInsight.findById(AID);
            if (row == null) {
                row = new WhoopInsight();
                row.athleteId = AID;
                row.persist();
            }
            row.runsDate = today;
            row.runsCount = WhoopResource.MAX_ANALYSES_PER_DAY;
        });
        given().when().post("/api/whoop/insights/analyze")
                .then().statusCode(429);

        // A stale runs_date (yesterday) resets the budget — keyless env then 503s,
        // proving the gate opened without burning toward a paid call.
        QuarkusTransaction.requiringNew().run(() -> {
            WhoopInsight row = WhoopInsight.findById(AID);
            row.runsDate = "2000-01-01";
        });
        given().when().post("/api/whoop/insights/analyze")
                .then().statusCode(503);
    }

    @Test
    void insightsReportsRunsLeft() {
        given().when().get("/api/whoop/insights")
                .then().statusCode(200)
                .body("ai_available", is(false));
    }

    @Test
    void rejectsNonWhoopZip() throws Exception {
        given().multiPart("file", "bad.zip", zipOf("readme.txt", "not a whoop export"), "application/zip")
                .when().post("/api/whoop/import")
                .then().statusCode(400);
    }

    private static byte[] zipOf(String name, String content) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry(name));
            z.write(content.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return bos.toByteArray();
    }

    // ── GET /api/whoop/status (bean si52) ────────────────────────────────────

    @Test
    void statusSeparatesDeploymentConfigFromAthleteConnection() {
        // Credentials pinned empty by the profile: this models a self-host install
        // that never set them. The UI keys the Connect button off `configured`, and
        // if this reported true that user would get a button that 400s.
        given().when().get("/api/whoop/status")
                .then().statusCode(200)
                .body("configured", is(false))
                .body("connected", is(false))
                .body("reconnect_required", is(false));
    }

    @Test
    void reconnectRequiredIsReportedOnlyWhileStillConnected() {
        // The state that matters: a spent refresh token leaves the athlete LOOKING
        // connected while data silently stops updating. The flag has to survive
        // alongside a populated token — clearing the token instead would destroy a
        // working credential whenever WHOOP merely had a bad minute.
        QuarkusTransaction.requiringNew().run(() -> {
            Athlete a = Athlete.findById(AID);
            a.whoopRefreshToken = "still-here";
            a.whoopReconnectRequired = true;
        });
        given().when().get("/api/whoop/status")
                .then().statusCode(200)
                .body("connected", is(true))
                .body("reconnect_required", is(true));

        // ...and once disconnected it must go quiet rather than nagging about a
        // connection the athlete deliberately removed.
        QuarkusTransaction.requiringNew().run(() -> {
            Athlete a = Athlete.findById(AID);
            a.whoopRefreshToken = null;
            a.whoopReconnectRequired = true;   // stale flag left behind on purpose
        });
        given().when().get("/api/whoop/status")
                .then().statusCode(200)
                .body("connected", is(false))
                .body("reconnect_required", is(false));
    }

    @Test
    void asyncSyncReturnsTheSharedJobEnvelope() {
        // The frontend's viaJob()/whoopSync() destructure `.job`. This endpoint
        // originally returned the job dict BARE, so every async caller read
        // undefined and threw while the job ran on regardless — invisible from the
        // backend, which had done nothing wrong. Pin the shape.
        //
        // No WHOOP credentials in this profile, so the job itself fails; the
        // envelope is what is under test, and it is present either way.
        given().when().post("/api/whoop/sync?async=1")
                .then().statusCode(200)
                .body("job.kind", is("whoop_sync"))
                .body("job.id", org.hamcrest.Matchers.notNullValue());
    }

    @Test
    void statusReportsTheEffectiveScheduleRatherThanAssumingOne() {
        // whoop.sync-cron is configurable and can be switched off. The UI renders
        // "Syncing daily at HH:00" from sync_hour, so a deployment that retimed or
        // disabled the job must not still be described as syncing at 10:00.
        // The default cron applies here, so both fields are populated.
        given().when().get("/api/whoop/status")
                .then().statusCode(200)
                .body("sync_cron", is("0 0 10 * * ?"))
                .body("sync_hour", is(10));
    }
}
