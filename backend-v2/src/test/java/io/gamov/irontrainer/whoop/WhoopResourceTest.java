package io.gamov.irontrainer.whoop;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gamov.irontrainer.athlete.Athlete;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** POST /api/whoop/import (multipart ZIP → per-day upsert) + GET /api/whoop/cycles.
 * Dedicated default athlete (7001) — same isolation trick as StravaImportEndpointTest. */
@QuarkusTest
@TestProfile(WhoopResourceTest.Profile.class)
class WhoopResourceTest {

    static final int AID = 7001;

    @InjectMock
    @org.eclipse.microprofile.rest.client.inject.RestClient
    WhoopApi whoopApi;

    @jakarta.inject.Inject
    WhoopSync whoopSync;

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

    @Test
    void callbackDoesNotRunTheBackfillInsideTheRequest() throws Exception {
        // The first sync is a FULL backfill — ~180 paged requests at 700ms pacing,
        // so ~128s of sleeping before any network time. Inline it cannot fit inside
        // an HTTP request: Cloudflare cuts the connection at 100s and the athlete
        // gets a 524 on a connection that actually SUCCEEDED. Production did exactly
        // that — backend 200 and Cloudflare 524, both at 18:30:13.
        //
        // So the assertion is wall-clock, because the bug was wall-clock. The WHOOP
        // client is mocked to BLOCK on the first data call: if the sync still runs
        // inline the callback cannot return, and this fails by timing out rather
        // than by a subtle wrong value.
        CountDownLatch syncStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Mockito.when(whoopApi.exchangeCode(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.any()))
                .thenReturn(Map.of("access_token", "at", "refresh_token", "rt",
                        "expires_in", 3600));
        Mockito.when(whoopApi.cycles(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyInt(), Mockito.any()))
                .thenAnswer(inv -> {
                    syncStarted.countDown();
                    release.await(30, TimeUnit.SECONDS);   // hold the sync open
                    return Map.of("records", List.of());
                });

        // A state the CSRF check will accept, so the handler reaches the sync.
        String state = "smoke-state-value";
        QuarkusTransaction.requiringNew().run(() -> {
            Athlete a = Athlete.findById(AID);
            a.whoopOauthState = state;
        });

        try {
            long start = System.nanoTime();
            given().redirects().follow(false)
                    .when().get("/api/whoop/callback?code=abc&state=" + state)
                    .then().statusCode(anyOf(is(302), is(303), is(307)));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(syncStarted.await(10, TimeUnit.SECONDS),
                    "the initial sync should have been queued and started");
            assertTrue(elapsedMs < 5_000,
                    "callback must redirect without waiting for the backfill, took "
                            + elapsedMs + "ms");
        } finally {
            release.countDown();
        }
    }


    @Test
    void catchUpStartsFromTheNewestStoredDayNotFiveYearsBack() throws Exception {
        // The point of the whole change: an athlete who already uploaded the export
        // ZIP has years of days on disk, and re-fetching them costs ~180 paged
        // requests and minutes of pacing to rewrite rows that were already right.
        // The ZIP row seeded by the import tests above is what makes this concrete —
        // source does not matter, a stored day is a covered day.
        // Its own athlete, seeded here. Sharing the default one meant either
        // depending on whichever test ran first (a silent skip, which tests
        // nothing) or clearing its rows (which broke the import test's counts).
        final int gapAid = 7002;
        String seeded = LocalDate.now(ZoneOffset.UTC).minusDays(40).toString();
        QuarkusTransaction.requiringNew().run(() -> {
            if (Athlete.findById(gapAid) == null) {
                Athlete.getEntityManager()
                        .createNativeQuery("INSERT INTO athlete (id) VALUES (" + gapAid + ")")
                        .executeUpdate();
            }
            WhoopCycle.delete("athleteId", gapAid);
            WhoopCycle row = new WhoopCycle();
            row.athleteId = gapAid;
            row.date = seeded;
            row.source = "zip";
            row.persist();
        });

        AtomicReference<String> requestedStart = new AtomicReference<>();
        Mockito.when(whoopApi.cycles(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyInt(), Mockito.any()))
                .thenAnswer(inv -> {
                    requestedStart.compareAndSet(null, (String) inv.getArgument(1));
                    return Map.of("records", List.of());
                });
        Mockito.when(whoopApi.recovery(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyInt(), Mockito.any())).thenReturn(Map.of("records", List.of()));
        Mockito.when(whoopApi.sleep(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyInt(), Mockito.any())).thenReturn(Map.of("records", List.of()));

        QuarkusTransaction.requiringNew().run(() -> {
            Athlete a = Athlete.findById(gapAid);
            a.whoopRefreshToken = "rt";
            a.whoopAccessToken = "at";
            a.whoopTokenExpiresAt = Instant.now().getEpochSecond() + 3600;
        });

        whoopSync.runCatchUp(gapAid);

        String start = requestedStart.get();
        org.junit.jupiter.api.Assertions.assertNotNull(start, "cycles should have been fetched");
        LocalDate asked = Instant.parse(start).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate expected = LocalDate.parse(seeded).minusDays(3);   // the overlap
        assertEquals(expected, asked,
                "catch-up must start just before the newest stored day, not years back");

        // And the bound that stops one ancient row causing an unbounded walk still
        // holds: the request is never earlier than the full window would be.
        assertTrue(!asked.isBefore(LocalDate.now(ZoneOffset.UTC).minusYears(50)),
                "catch-up must stay inside the configured history window");
    }
}
