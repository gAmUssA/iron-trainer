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

        List<WhoopCycle> cycles = WhoopArchive.parse(zipWith("physiological_cycles.csv", csv)).cycles();
        // ONE row, not two. Both CSV rows derive to 2026-07-28 — the second is an
        // open cycle whose 02:05 UTC start is 22:05 the PREVIOUS local day — so this
        // fixture is itself a two-cycle day, and dedupeByDate collapses it (80i2).
        // It always collapsed; before the rule existed the (athlete, date) key just
        // decided it later and by arrival order. The scored cycle wins.
        assertEquals(1, cycles.size());

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

    }

    @Test
    void anOpenCycleFallsBackToItsStartAndRespectsTheOffset() {
        // Split out of the test above, which could no longer assert this: its open
        // cycle collided with the scored one and is now collapsed away. Here the
        // open cycle owns its own date, so the fallback ladder stays covered.
        // 2026-07-30 02:05 UTC at UTC-04:00 is 22:05 on 2026-07-29 locally.
        String csv = String.join("\n", HEADER,
                "2026-07-30 02:05:00,,UTC-04:00,,,,,,5.1,890,140,95,,,,,,,") + "\n";
        List<WhoopCycle> cycles;
        try {
            cycles = WhoopArchive.parse(zipWith("physiological_cycles.csv", csv)).cycles();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals(1, cycles.size());
        WhoopCycle open = cycles.get(0);
        assertEquals("2026-07-29", open.date);
        assertNull(open.recoveryScore);
        assertEquals(5.1, open.dayStrain);
    }

    @Test
    void positiveOffsetCrossesDateBoundary() throws Exception {
        String csv = HEADER + "\n"
                + "2026-07-28 12:00:00,2026-07-29 14:00:00,UTC+08:00,50,55,40,33,95,10,2000,"
                + "160,90,2026-07-28 12:30:00,2026-07-28 22:30:00,80,15,400,420,90\n";
        List<WhoopCycle> cycles = WhoopArchive.parse(zipWith("physiological_cycles.csv", csv)).cycles();
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
                WhoopArchive.parse(zipWith("my_whoop_data_2026_07_29/physiological_cycles.csv", csv)).cycles();
        WhoopCycle c = cycles.get(0);
        assertEquals(67.0, c.recoveryScore);
        assertEquals(52.0, c.rhrBpm);
        assertEquals(48.5, c.hrvRmssdMs);
        assertEquals(7.2, c.asleepH, 1e-9);
    }

    @Test
    void parsesJournalJoinedToCycleWakeDate() throws Exception {
        String cyclesCsv = HEADER + "\n"
                // Wake 10:41 UTC-04:00 → date 2026-07-28; cycle ends AFTER local
                // midnight (01:58 UTC 29th = 21:58 local 28th is fine, so use a
                // late bedtime: end 2026-07-29 05:30 UTC = 01:30 local 29th).
                + "2026-07-28 02:14:30,2026-07-29 05:30:00,UTC-04:00,67,52,48.5,33.9,96,14.2,2456,"
                + "171,88,2026-07-28 02:20:00,2026-07-28 10:41:12,89,16.1,432,470,92\n";
        String journalCsv = String.join("\n",
                "Cycle start time,Cycle end time,Cycle timezone,Question text,Answered yes,Notes",
                // Joined by cycle start → inherits wake date 2026-07-28, NOT the
                // end-date (2026-07-29 local) an end-time heuristic would pick.
                "2026-07-28 02:14:30,2026-07-29 05:30:00,UTC-04:00,Have any alcoholic drinks?,true,two beers",
                "2026-07-28 02:14:30,2026-07-29 05:30:00,UTC-04:00,Consumed caffeine?,false,",
                // Unknown cycle → falls back to local end date.
                "2026-06-01 02:00:00,2026-06-02 01:00:00,UTC-04:00,Consumed caffeine?,true,") + "\n";

        Path zip = tmp.resolve("export.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("physiological_cycles.csv"));
            z.write(cyclesCsv.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("journal_entries.csv"));
            z.write(journalCsv.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        List<WhoopJournalEntry> journal = WhoopArchive.parse(zip).journal();
        assertEquals(3, journal.size());
        assertEquals("2026-07-28", journal.get(0).date);
        assertEquals("Have any alcoholic drinks?", journal.get(0).question);
        assertEquals(true, journal.get(0).answeredYes);
        assertEquals("two beers", journal.get(0).notes);
        assertEquals(false, journal.get(1).answeredYes);
        // Fallback path: 01:00 UTC on 06-02 minus 4 h → local 2026-06-01.
        assertEquals("2026-06-01", journal.get(2).date);
    }

    @Test
    void journalIsOptional() throws Exception {
        String csv = HEADER + "\n"
                + "2026-07-28 02:14:30,2026-07-29 01:58:00,UTC-04:00,67,52,48.5,33.9,96,14.2,2456,"
                + "171,88,2026-07-28 02:20:00,2026-07-28 10:41:12,89,16.1,432,470,92\n";
        WhoopArchive.Export export = WhoopArchive.parse(zipWith("physiological_cycles.csv", csv));
        assertEquals(1, export.cycles().size());
        assertEquals(0, export.journal().size());
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
        assertEquals(0, WhoopArchive.parse(zipWith("physiological_cycles.csv", csv)).cycles().size());
    }
}
