package io.gamov.irontrainer.strava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.garmin.fit.BufferEncoder;
import com.garmin.fit.DateTime;
import com.garmin.fit.FileIdMesg;
import com.garmin.fit.Fit;
import com.garmin.fit.Manufacturer;
import com.garmin.fit.RecordMesg;
import com.garmin.fit.SessionMesg;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Parser parity for the Strava GDPR export ZIP → activity dicts (StravaArchive),
 * covering activities.csv summaries plus .gpx / .tcx / .fit(.gz) stream enrichment.
 * Port fidelity vs app/strava_import.py: CSV date/number parsing, stream-derived
 * averages (banker's rounding), and the FIT session-summary precedence. */
class StravaArchiveTest {

    @TempDir
    Path tmp;

    @Test
    void parsesCsvGpxTcxAndFit() throws Exception {
        // 1001 → GPX enrichment, 1002 → CSV-only, 1003 → TCX, 1004 → FIT(.gz)
        String csv = String.join("\n",
                "Activity ID,Activity Date,Activity Name,Activity Type,Elapsed Time,Distance,"
                        + "Filename,Moving Time,Max Heart Rate,Average Heart Rate,Average Watts,"
                        + "Average Speed,Elevation Gain",
                "1001,\"Apr 30, 2021, 1:30:45 PM\",Morning Ride,Ride,3700,30000,"
                        + "activities/1001.gpx,3600,175,150,,8.3,120",
                "1002,\"May 1, 2021, 6:00:00 AM\",Easy Run,Run,1800,5000,,1800,160,140,,2.7,20",
                "1003,\"Jun 2, 2021, 7:15:00 AM\",Trainer,Ride,2400,0,activities/1003.tcx,"
                        + "2400,,,,0,0",
                "1004,\"Jul 3, 2021, 5:00:00 PM\",Power Ride,Ride,3600,40000,"
                        + "activities/1004.fit.gz,3600,,,,10,200") + "\n";

        Path zip = tmp.resolve("export.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(z, "activities.csv", csv.getBytes(StandardCharsets.UTF_8));
            put(z, "activities/1001.gpx", gpx(200, 150, 60).getBytes(StandardCharsets.UTF_8));
            put(z, "activities/1003.tcx", tcx(250, 145, 40).getBytes(StandardCharsets.UTF_8));
            put(z, "activities/1004.fit.gz", gzip(fitWithSession()));
        }

        List<Map<String, Object>> acts = StravaArchive.parse(zip);
        assertEquals(4, acts.size());
        Map<Long, Map<String, Object>> byId = new java.util.HashMap<>();
        for (Map<String, Object> a : acts) {
            byId.put((Long) a.get("id"), a);
        }

        // 1001: CSV summary + GPX stream enrichment (constant 200 W / 150 bpm).
        Map<String, Object> ride = byId.get(1001L);
        assertEquals("2021-04-30T13:30:45", ride.get("start_date_local"));
        assertEquals("Ride", ride.get("type"));
        assertEquals("Morning Ride", ride.get("name"));
        assertEquals(30000.0, ride.get("distance"));
        assertEquals(3600.0, ride.get("moving_time"));
        assertEquals(Boolean.TRUE, ride.get("device_watts"));
        assertEquals(200L, ride.get("average_watts"));
        assertEquals(200L, ride.get("weighted_average_watts"));   // constant power → NP = mean
        assertEquals(150L, ride.get("average_heartrate"));        // GPX overrides the CSV 150
        assertEquals(150L, ride.get("max_heartrate"));            // GPX max overrides CSV 175

        // 1002: CSV-only (no filename) — numbers straight from the row, no streams.
        Map<String, Object> run = byId.get(1002L);
        assertEquals("2021-05-01T06:00:00", run.get("start_date_local"));
        assertEquals(140.0, run.get("average_heartrate"));   // CSV double, not stream-rounded
        assertEquals(160.0, run.get("max_heartrate"));
        assertNull(run.get("device_watts"));
        assertNull(run.get("average_watts"));

        // 1003: TCX enrichment (250 W / 145 bpm).
        Map<String, Object> trainer = byId.get(1003L);
        assertEquals(250L, trainer.get("average_watts"));
        assertEquals(Boolean.TRUE, trainer.get("device_watts"));
        assertEquals(145L, trainer.get("average_heartrate"));

        // 1004: FIT — session summary takes precedence (NP 205, avg_hr 160, max_hr
        // 175) but record-derived average_watts (200) wins over session avg_power.
        Map<String, Object> power = byId.get(1004L);
        assertEquals(Boolean.TRUE, power.get("device_watts"));
        assertEquals(200L, power.get("average_watts"));
        assertEquals(205L, power.get("weighted_average_watts"));
        assertEquals(160L, power.get("average_heartrate"));
        assertEquals(175L, power.get("max_heartrate"));
    }

    @Test
    void missingActivitiesCsvIsRejected() throws Exception {
        Path zip = tmp.resolve("bad.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(z, "readme.txt", "not a strava export".getBytes(StandardCharsets.UTF_8));
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> StravaArchive.parse(zip));
        assertTrue(e.getMessage().contains("activities.csv"), e.getMessage());
    }

    @Test
    void csvNumberAndDateFallbacks() {
        // thousands commas stripped; blank → null; unparseable date stored as-is.
        assertEquals(1234.5, StravaArchive.num("1,234.5"));
        assertNull(StravaArchive.num("  "));
        assertNull(StravaArchive.num(null));
        assertEquals("2021-04-30T13:30:45", StravaArchive.parseDate("Apr 30, 2021, 1:30:45 PM"));
        assertEquals("not a date", StravaArchive.parseDate("not a date"));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static void put(ZipOutputStream z, String name, byte[] data) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    private static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    /** GPX with plain (unprefixed) <power>/<hr> leaf elements per trackpoint. */
    private static String gpx(int watts, int bpm, int n) {
        StringBuilder sb = new StringBuilder("<gpx><trk><trkseg>");
        for (int i = 0; i < n; i++) {
            sb.append("<trkpt><power>").append(watts).append("</power>")
                    .append("<hr>").append(bpm).append("</hr></trkpt>");
        }
        return sb.append("</trkseg></trk></gpx>").toString();
    }

    private static String tcx(int watts, int bpm, int n) {
        StringBuilder sb = new StringBuilder(
                "<TrainingCenterDatabase><Activities><Activity><Lap><Track>");
        for (int i = 0; i < n; i++) {
            sb.append("<Trackpoint><HeartRateBpm><Value>").append(bpm).append("</Value></HeartRateBpm>")
                    .append("<Watts>").append(watts).append("</Watts></Trackpoint>");
        }
        return sb.append("</Track></Lap></Activity></Activities></TrainingCenterDatabase>").toString();
    }

    /** A valid activity FIT: 60 records @200 W / 150 bpm + a session summary whose
     * NP/avg_hr/max_hr differ from the records so precedence is observable. */
    private static byte[] fitWithSession() {
        BufferEncoder enc = new BufferEncoder(Fit.ProtocolVersion.V2_0);
        FileIdMesg fileId = new FileIdMesg();
        fileId.setType(com.garmin.fit.File.ACTIVITY);
        fileId.setManufacturer(Manufacturer.DEVELOPMENT);
        enc.write(fileId);
        long base = 1609459200L;   // 2021-01-01 in unix seconds
        for (int i = 0; i < 60; i++) {
            RecordMesg r = new RecordMesg();
            r.setTimestamp(new DateTime(new Date((base + i) * 1000L)));
            r.setPower(200);
            r.setHeartRate((short) 150);
            enc.write(r);
        }
        SessionMesg s = new SessionMesg();
        s.setNormalizedPower(205);
        s.setAvgPower(999);          // must be IGNORED (records already set average_watts)
        s.setAvgHeartRate((short) 160);
        s.setMaxHeartRate((short) 175);
        enc.write(s);
        return enc.close();
    }
}
