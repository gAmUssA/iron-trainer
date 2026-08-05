package io.gamov.irontrainer.whoop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** WHOOP export ZIP → per-day WhoopCycle rows: wake-date attribution with the
 * cycle timezone offset, minutes→hours, header-drift tolerance, and the
 * not-a-WHOOP-export rejection. */
class WhoopArchiveTest {

    @TempDir
    Path tmp;

    private static final String HEADER =
            "Cycle start time,Cycle end time,Cycle timezone,Recovery score %,"
                    + "Resting heart rate (bpm),Heart rate variability (ms),Skin temp (celsius),"
                    + "Blood oxygen %,Day Strain,Energy burned (cal),Max HR (bpm),Average HR (bpm),"
                    + "Sleep onset,Wake onset,Sleep performance %,Respiratory rate (rpm),"
                    + "Asleep duration (min),In bed duration (min),Sleep efficiency %";

    private Path zipWith(String csvName, String csv) throws Exception {
        Path zip = tmp.resolve("export.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry(csvName));
            z.write(csv.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return zip;
    }

    @Test
    void parsesCyclesWithWakeDateAndUnits() throws Exception {
        String csv = String.join("\n",
                HEADER,
                // UTC wake 10:41 at UTC-04:00 → local 06:41 on 2026-07-28.
                "2026-07-28 02:14:30,2026-07-29 01:58:00,UTC-04:00,67,52,48.5,33.9,96,14.2,2456,"
                        + "171,88,2026-07-28 02:20:00,2026-07-28 10:41:12,89,16.1,432,470,92",
                // Open cycle: no wake/end yet → date falls back to cycle start; blank score.
                "2026-07-29 02:05:00,,UTC-04:00,,,,,,5.1,890,140,95,,,,,,,") + "\n";

        List<WhoopCycle> cycles = WhoopArchive.parse(zipWith("physiological_cycles.csv", csv));
        assertEquals(2, cycles.size());

        WhoopCycle c = cycles.get(0);
        assertEquals("2026-07-28", c.date);
        assertEquals(67.0, c.recoveryScore);
        assertEquals(48.5, c.hrvRmssdMs);
        assertEquals(52.0, c.rhrBpm);
        assertEquals(14.2, c.dayStrain);
        assertEquals(2456.0, c.energyKcal);
        assertEquals(96.0, c.spo2Pct);
        assertEquals(33.9, c.skinTempC);
        assertEquals(89.0, c.sleepPerformancePct);
        assertEquals(92.0, c.sleepEfficiencyPct);
        assertEquals(16.1, c.respiratoryRate);
        assertEquals(7.2, c.asleepH, 1e-9);   // 432 min

        WhoopCycle open = cycles.get(1);
        // 02:05 UTC - 4 h = 22:05 the previous local day.
        assertEquals("2026-07-28", open.date);
        assertNull(open.recoveryScore);
        assertEquals(5.1, open.dayStrain);
    }

    @Test
    void positiveOffsetCrossesDateBoundary() throws Exception {
        String csv = HEADER + "\n"
                + "2026-07-28 12:00:00,2026-07-29 14:00:00,UTC+08:00,50,55,40,33,95,10,2000,"
                + "160,90,2026-07-28 12:30:00,2026-07-28 22:30:00,80,15,400,420,90\n";
        List<WhoopCycle> cycles = WhoopArchive.parse(zipWith("physiological_cycles.csv", csv));
        // UTC 22:30 + 8 h = 06:30 next local day.
        assertEquals("2026-07-29", cycles.get(0).date);
    }

    @Test
    void toleratesRenamedHeadersAndNestedPath() throws Exception {
        // Renames WHOOP has shipped or could ship: unit suffix dropped / added.
        String csv = String.join("\n",
                "Cycle start time,Cycle end time,Cycle timezone,Recovery Score,"
                        + "Resting heart rate,Heart rate variability,Day Strain,Wake onset,"
                        + "Asleep duration (min)",
                "2026-07-28 02:14:30,2026-07-29 01:58:00,UTC-04:00,67,52,48.5,14.2,"
                        + "2026-07-28 10:41:12,432") + "\n";
        List<WhoopCycle> cycles =
                WhoopArchive.parse(zipWith("my_whoop_data_2026_07_29/physiological_cycles.csv", csv));
        WhoopCycle c = cycles.get(0);
        assertEquals(67.0, c.recoveryScore);
        assertEquals(52.0, c.rhrBpm);
        assertEquals(48.5, c.hrvRmssdMs);
        assertEquals(7.2, c.asleepH, 1e-9);
    }

    @Test
    void rejectsNonWhoopZip() throws Exception {
        Path zip = zipWith("activities.csv", "Activity ID\n123\n");
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> WhoopArchive.parse(zip));
        assertEquals("No physiological_cycles.csv found — is this a WHOOP data export ZIP?", e.getMessage());
    }

    @Test
    void skipsRowsWithoutAnyTimestamp() throws Exception {
        String csv = HEADER + "\n" + ",,UTC-04:00,67,52,48.5,,,,,,,,,,,,,\n";
        assertEquals(0, WhoopArchive.parse(zipWith("physiological_cycles.csv", csv)).size());
    }
}
