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
            return Map.of("irontrainer.default-athlete-id", String.valueOf(AID));
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
}
