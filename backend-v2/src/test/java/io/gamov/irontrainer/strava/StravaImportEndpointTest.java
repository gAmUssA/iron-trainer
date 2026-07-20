package io.gamov.irontrainer.strava;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasKey;
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

/** POST /api/strava/import — multipart upload of a Strava export ZIP → parse +
 * upsert + dedup + seed + rebuild, returning the import summary. Uses a dedicated
 * default athlete (5001) so the activities FK is satisfied without colliding with
 * the serial ids other tests generate. */
@QuarkusTest
@TestProfile(StravaImportEndpointTest.Profile.class)
class StravaImportEndpointTest {

    static final int AID = 5001;

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

    @Test
    void importsExportZip() throws Exception {
        given().multiPart("file", "export.zip", exportZip(), "application/zip")
                .when().post("/api/strava/import")
                .then().statusCode(200)
                .body("parsed", is(2))
                .body("upserted", is(2))
                .body("with_streams", is(1))       // only the GPX activity has power streams
                .body("total_activities", greaterThanOrEqualTo(2))
                .body("$", hasKey("duplicates_removed"))
                .body("$", hasKey("metrics_days"))
                .body("$", hasKey("profile_seeded"));
    }

    @Test
    void rejectsNonExportZip() throws Exception {
        given().multiPart("file", "bad.zip", zipOf("readme.txt", "not a strava export"),
                        "application/zip")
                .when().post("/api/strava/import")
                .then().statusCode(400);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static byte[] exportZip() throws Exception {
        String csv = String.join("\n",
                "Activity ID,Activity Date,Activity Name,Activity Type,Elapsed Time,Distance,"
                        + "Filename,Moving Time,Max Heart Rate,Average Heart Rate,Average Watts,"
                        + "Average Speed,Elevation Gain",
                // Recent dates so they survive the 5-year retention prune.
                "7001,\"Apr 30, 2026, 1:30:45 PM\",Ride,Ride,3700,30000,activities/7001.gpx,"
                        + "3600,175,150,,8.3,120",
                "7002,\"May 1, 2026, 6:00:00 AM\",Run,Run,1800,5000,,1800,160,140,,2.7,20") + "\n";
        StringBuilder gpx = new StringBuilder("<gpx><trk><trkseg>");
        for (int i = 0; i < 60; i++) {
            gpx.append("<trkpt><power>200</power><hr>150</hr></trkpt>");
        }
        gpx.append("</trkseg></trk></gpx>");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            put(z, "activities.csv", csv.getBytes(StandardCharsets.UTF_8));
            put(z, "activities/7001.gpx", gpx.toString().getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }

    private static byte[] zipOf(String name, String content) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            put(z, name, content.getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }
}
