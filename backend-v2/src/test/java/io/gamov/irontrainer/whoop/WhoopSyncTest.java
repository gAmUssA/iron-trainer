package io.gamov.irontrainer.whoop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure mapping tests for the WHOOP API → whoop_cycles conversion (bean 4a6s).
 *
 * <p>These cover the parts that corrupt data SILENTLY when wrong: the join key, the
 * score-state guard, and the unit conversions. Every one of them still produces a
 * plausible-looking row when broken, which is exactly why they are pinned here
 * rather than left to be noticed on a chart.
 */
class WhoopSyncTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> cycle(long id, String start, String end, String tz,
                                             String state, Double strain, Double kj) {
        return map("id", id, "start", start, "end", end, "timezone_offset", tz,
                "score_state", state, "updated_at", "2026-08-20T12:00:00.000Z",
                "score", map("strain", strain, "kilojoule", kj));
    }

    // ── the join key ─────────────────────────────────────────────────────────

    @Test
    void dateComesFromLocalWakeTimeNotUtc() {
        // Wake at 05:30Z on the 15th, in UTC-07:00, is still the 14th locally.
        // Getting this wrong shifts an athlete's whole history by a day against
        // the ZIP rows and produces two entries per physiological day.
        Map<String, Object> c = cycle(1L, "2026-08-14T10:00:00.000Z",
                "2026-08-15T10:00:00.000Z", "-07:00", "SCORED", 12.0, null);
        Map<String, Object> sleep = map("cycle_id", 1L, "nap", false,
                "end", "2026-08-15T05:30:00.000Z", "score_state", "SCORED",
                "score", map());

        WhoopCycle row = WhoopSync.toRow(c, Map.of(), Map.of(1L, sleep));
        assertEquals("2026-08-14", row.date);
    }

    @Test
    void fallsBackToCycleEndThenStartWhenThereIsNoSleep() {
        // An open or unslept cycle must still land on a day, using the same ladder
        // WhoopArchive uses: wake -> cycle end -> cycle start.
        Map<String, Object> withEnd = cycle(2L, "2026-08-14T10:00:00.000Z",
                "2026-08-15T09:00:00.000Z", "+00:00", "SCORED", 8.0, null);
        assertEquals("2026-08-15", WhoopSync.toRow(withEnd, Map.of(), Map.of()).date);

        Map<String, Object> openCycle = cycle(3L, "2026-08-16T10:00:00.000Z",
                null, "+00:00", "PENDING_SCORE", null, null);
        assertEquals("2026-08-16", WhoopSync.toRow(openCycle, Map.of(), Map.of()).date);
    }

    @Test
    void aCycleWithNoUsableTimestampIsDropped() {
        assertNull(WhoopSync.toRow(cycle(4L, null, null, "+00:00", "PENDING_SCORE", null, null),
                Map.of(), Map.of()));
    }

    // ── the score-state guard ────────────────────────────────────────────────

    @Test
    void pendingCyclesContributeNoMetrics() {
        // The 10:00 poll routinely catches this morning's cycle as PENDING_SCORE.
        // It may create the day, but must carry no numbers — writing nulls over a
        // scored day is the failure the guard exists to prevent, and the timestamp
        // check would NOT catch it because updated_at really is newer.
        Map<String, Object> pending = cycle(5L, "2026-08-20T10:00:00.000Z",
                "2026-08-21T09:00:00.000Z", "+00:00", "PENDING_SCORE", 99.0, 9999.0);
        WhoopCycle row = WhoopSync.toRow(pending, Map.of(), Map.of());
        assertEquals("2026-08-21", row.date);
        assertNull(row.dayStrain, "an unscored cycle must not report strain");
        assertNull(row.energyKcal, "an unscored cycle must not report energy");
    }

    @Test
    void mergeNeverBlanksAnExistingValueWithNull() {
        // The other half of the guard: a later unscored fetch must not wipe numbers
        // an earlier scored fetch (or the ZIP) already supplied.
        WhoopCycle stored = new WhoopCycle();
        stored.date = "2026-08-20";
        stored.recoveryScore = 66.0;
        stored.dayStrain = 14.2;
        stored.source = "zip";

        WhoopCycle incoming = new WhoopCycle();
        incoming.date = "2026-08-20";
        incoming.source = "api";
        incoming.dayStrain = 15.0;      // scored
        // recoveryScore left null — recovery still pending

        new WhoopSync().upsertMergeForTest(stored, incoming);

        assertEquals(15.0, stored.dayStrain, "a fresh scored value should win");
        assertEquals(66.0, stored.recoveryScore, "a null must not blank an existing value");
        assertEquals("api", stored.source);
    }

    // ── unit conversions ─────────────────────────────────────────────────────

    @Test
    void kilojoulesBecomeKilocalories() {
        // The ZIP column is "Energy burned (cal)" meaning kcal; the API sends kJ.
        // Skipping the conversion inflates every day by 4.184x, which looks like a
        // plausible number rather than an obvious bug.
        Map<String, Object> c = cycle(6L, "2026-08-14T10:00:00.000Z",
                "2026-08-15T09:00:00.000Z", "+00:00", "SCORED", 12.0, 8368.0);
        WhoopCycle row = WhoopSync.toRow(c, Map.of(), Map.of());
        assertEquals(2000.0, row.energyKcal, 0.01);
    }

    @Test
    void asleepHoursSumOnlyTheAsleepStages() {
        // stage_summary carries awake time and disturbance counts too; including
        // them would overstate sleep. 2h light + 1h SWS + 1h REM = 4h.
        Map<String, Object> stages = map(
                "total_light_sleep_time_milli", 7_200_000.0,
                "total_slow_wave_sleep_time_milli", 3_600_000.0,
                "total_rem_sleep_time_milli", 3_600_000.0,
                "total_awake_time_milli", 1_800_000.0);
        assertEquals(4.0, WhoopSync.asleepHours(stages), 1e-9);
    }

    @Test
    void asleepHoursIsNullNotZeroWhenNoStagesArePresent() {
        // 0.0 would read as "slept nothing" on a chart; null reads as "no data".
        assertNull(WhoopSync.asleepHours(Map.of()));
    }

    // ── timezone + timestamp handling ────────────────────────────────────────

    @Test
    void offsetParsingHandlesWhoopsFormatAndFallsBackToUtc() {
        assertEquals(-4 * 3600, WhoopSync.offsetSeconds("-04:00"));
        assertEquals(5 * 3600 + 1800, WhoopSync.offsetSeconds("+05:30"));
        assertEquals(0, WhoopSync.offsetSeconds(null));
        assertEquals(0, WhoopSync.offsetSeconds("nonsense"));
    }

    @Test
    void newestPicksTheLatestNonNullTimestamp() {
        assertEquals("2026-08-20T12:00:00.000Z",
                WhoopSync.newest("2026-08-19T00:00:00.000Z", null, "2026-08-20T12:00:00.000Z"));
        assertNull(WhoopSync.newest(null, null));
    }

    // ── naps ─────────────────────────────────────────────────────────────────

    @Test
    void napsAreNotUsedForTheJoinKey() {
        // A nap's end time would move the day's wake date. Verified via toRow with
        // the nap already filtered out, which is what the sync does before indexing.
        Map<String, Object> c = cycle(7L, "2026-08-14T10:00:00.000Z",
                "2026-08-15T09:00:00.000Z", "+00:00", "SCORED", 10.0, null);
        // Empty sleep index == the nap was filtered; falls back to cycle end.
        assertEquals("2026-08-15", WhoopSync.toRow(c, Map.of(), Map.of()).date);
    }

    @Test
    void sleepScoresAreReadFromTheSleepRecord() {
        Map<String, Object> c = cycle(8L, "2026-08-14T10:00:00.000Z",
                "2026-08-15T09:00:00.000Z", "+00:00", "SCORED", 10.0, null);
        Map<String, Object> sleep = map("cycle_id", 8L, "nap", false,
                "end", "2026-08-15T06:00:00.000Z", "score_state", "SCORED",
                "score", map("sleep_performance_percentage", 88.0,
                        "sleep_efficiency_percentage", 91.5,
                        "respiratory_rate", 14.2,
                        "stage_summary", map("total_rem_sleep_time_milli", 3_600_000.0)));
        WhoopCycle row = WhoopSync.toRow(c, Map.of(), Map.of(8L, sleep));
        assertEquals(88.0, row.sleepPerformancePct);
        assertEquals(91.5, row.sleepEfficiencyPct);
        assertEquals(14.2, row.respiratoryRate);
        assertEquals(1.0, row.asleepH, 1e-9);
        assertTrue("api".equals(row.source));
    }
}
