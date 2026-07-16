package io.gamov.irontrainer.readiness;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mirrors backend/tests/test_readiness.py — the Python suite is the spec.
 * Pure-function tests; endpoint + reason-string parity is the seeded gate. */
class ReadinessTest {

    static final LocalDate TODAY = LocalDate.of(2026, 7, 14);

    private static List<Map<String, Object>> rows(Map<Integer, Double> daily, Double tsb, int days) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int ago = days; ago >= 1; ago--) {
            Map<String, Object> r = new HashMap<>();
            r.put("date", TODAY.minusDays(ago).toString());
            r.put("tss", daily.getOrDefault(ago, 0.0));
            r.put("ctl", 50.0); r.put("atl", 50.0); r.put("tsb", tsb);
            out.add(r);
        }
        return out;
    }

    private static List<Map<String, Object>> flat(double weekTss, Double tsb, int days) {
        double per = weekTss / 7.0;
        Map<Integer, Double> m = new HashMap<>();
        for (int a = 1; a <= days; a++) m.put(a, per);
        return rows(m, tsb, days);
    }

    @SuppressWarnings("unchecked")
    private static List<String> reasons(Map<String, Object> r) {
        return (List<String>) r.get("reasons");
    }

    @Test
    void insufficientHistory() {
        Map<String, Object> out = Readiness.compute(flat(400, 0.0, 7), List.of(), TODAY);
        assertEquals("insufficient_data", out.get("status"));
        assertNull(out.get("call"));
        assertNull(Readiness.storyLine(out));
    }

    @Test
    void steadyLoadIsGreenHard() {
        Map<String, Object> out = Readiness.compute(flat(400, 0.0, 42), List.of(), TODAY);
        assertEquals("ok", out.get("status"));
        assertEquals("hard", out.get("call"));
        assertEquals("green", out.get("level"));
        assertEquals(1.0, out.get("acwr"));
        assertTrue(reasons(out).get(0).contains("ratio 1.00"));
    }

    @Test
    void acwrSpikeIsRedRest() {
        Map<Integer, Double> daily = new HashMap<>();
        for (int a = 1; a <= 28; a++) daily.put(a, 400.0 / 7);
        for (int a = 1; a <= 7; a++) daily.put(a, 800.0 / 7);
        Map<String, Object> out = Readiness.compute(rows(daily, 0.0, 42), List.of(), TODAY);
        assertEquals("rest", out.get("call"));
        assertEquals("red", out.get("level"));
        assertEquals(1.6, out.get("acwr"));
    }

    @Test
    void deepFatigueOverridesSteady() {
        Map<String, Object> out = Readiness.compute(flat(400, -30.0, 42), List.of(), TODAY);
        assertEquals("easy", out.get("call"));
        assertTrue(reasons(out).get(0).contains("TSB"));
    }

    @Test
    void storyLineFormatsCall() {
        Map<String, Object> out = Readiness.compute(flat(400, 0.0, 42), List.of(), TODAY);
        assertTrue(Readiness.storyLine(out).startsWith("Today's call: GO HARD — "));
    }

    /** 10 baseline days (sleep/hrv/rhr) ending the day before `latest`, plus a
     * latest row `latestAgo` days ago with its own values. Mirrors the shape
     * repo.recent_recovery() feeds compute(). */
    private static List<Map<String, Object>> recovery(
            double latestSleep, double latestHrv, double latestRhr,
            double baseSleep, double baseHrv, double baseRhr, int latestAgo) {
        List<Map<String, Object>> rec = new ArrayList<>();
        for (int ago = 10; ago >= 1; ago--) {
            Map<String, Object> r = new HashMap<>();
            r.put("date", TODAY.minusDays(ago + latestAgo).toString());
            r.put("sleep_h", baseSleep); r.put("hrv_ms", baseHrv); r.put("rhr_bpm", baseRhr);
            rec.add(r);
        }
        Map<String, Object> latest = new HashMap<>();
        latest.put("date", TODAY.minusDays(latestAgo).toString());
        latest.put("sleep_h", latestSleep); latest.put("hrv_ms", latestHrv); latest.put("rhr_bpm", latestRhr);
        rec.add(latest);
        return rec;
    }

    @Test
    void recoveryFlagDowngradesGreenToEasy() {
        // Today's HRV 40 vs the ~60 baseline (< 0.80x) → suppressed → green→easy.
        List<Map<String, Object>> rec = recovery(7.5, 40.0, 46.0, 7.5, 60.0, 46.0, 0);
        Map<String, Object> out = Readiness.compute(flat(400, 0.0, 42), rec, TODAY);
        assertEquals("easy", out.get("call"));
        assertTrue(reasons(out).get(0).contains("HRV suppressed"));
    }

    @Test
    void shortSleepDowngradesGreenToEasy() {
        // Latest sleep 5.0h (< 6h) → short-sleep flag leads and downgrades.
        List<Map<String, Object>> rec = recovery(5.0, 60.0, 46.0, 7.5, 60.0, 46.0, 0);
        Map<String, Object> out = Readiness.compute(flat(400, 0.0, 42), rec, TODAY);
        assertEquals("easy", out.get("call"));
        assertTrue(reasons(out).get(0).contains("Short sleep"));
    }

    @Test
    void elevatedRhrDowngradesGreenToEasy() {
        // Latest RHR 55 vs ~46 baseline (> +5 bpm) → elevated-RHR flag downgrades.
        List<Map<String, Object>> rec = recovery(7.5, 60.0, 55.0, 7.5, 60.0, 46.0, 0);
        Map<String, Object> out = Readiness.compute(flat(400, 0.0, 42), rec, TODAY);
        assertEquals("easy", out.get("call"));
        assertTrue(reasons(out).stream().anyMatch(r -> r.contains("Resting HR elevated")));
    }

    @Test
    void staleRecoveryIsIgnored() {
        // Latest recovery is 5 days old (> RECOVERY_FRESH_DAYS): a phone that
        // stopped pushing is not a bad night — no flags, call stays hard.
        List<Map<String, Object>> rec = recovery(4.0, 30.0, 60.0, 7.5, 60.0, 46.0, 5);
        Map<String, Object> out = Readiness.compute(flat(400, 0.0, 42), rec, TODAY);
        assertEquals("hard", out.get("call"));
        assertTrue(reasons(out).stream().noneMatch(
                r -> r.contains("Short sleep") || r.contains("HRV") || r.contains("Resting HR")));
    }

    @Test
    void normalRecoveryLeavesCallUnchanged() {
        // Recovery present but all within baseline → no flags, call stays hard.
        List<Map<String, Object>> rec = recovery(7.5, 60.0, 46.0, 7.5, 60.0, 46.0, 0);
        Map<String, Object> out = Readiness.compute(flat(400, 0.0, 42), rec, TODAY);
        assertEquals("hard", out.get("call"));
        assertEquals("green", out.get("level"));
    }

    @Test
    void insufficientChronicLoad() {
        // Enough history (42 days) but chronic weekly TSS below the 30 floor.
        Map<String, Object> out = Readiness.compute(flat(10, 0.0, 42), List.of(), TODAY);
        assertEquals("insufficient_data", out.get("status"));
    }

    @Test
    void acwrElevatedIsAmberEasy() {
        // acute 600, chronic (600 + 3*400)/4 = 450 -> ~1.33 (elevated band).
        Map<Integer, Double> daily = new HashMap<>();
        for (int a = 1; a <= 28; a++) daily.put(a, 400.0 / 7);
        for (int a = 1; a <= 7; a++) daily.put(a, 600.0 / 7);
        Map<String, Object> out = Readiness.compute(rows(daily, 0.0, 42), List.of(), TODAY);
        assertEquals("easy", out.get("call"));
        assertEquals("amber", out.get("level"));
        double acwr = (double) out.get("acwr");
        assertTrue(acwr > 1.3 && acwr <= 1.5, "acwr in elevated band, was " + acwr);
    }

    @Test
    void backToBackHardDaysAmber() {
        // Steady ~400/wk, but yesterday and the day before were ~120 TSS each:
        // acute stays under the 1.3 band, so the streak (not ACWR) drives the call.
        Map<Integer, Double> daily = new HashMap<>();
        for (int a = 1; a <= 42; a++) daily.put(a, 400.0 / 7);
        daily.put(1, 120.0);
        daily.put(2, 120.0);
        Map<String, Object> out = Readiness.compute(rows(daily, 0.0, 42), List.of(), TODAY);
        assertTrue((double) out.get("acwr") < 1.3);
        assertEquals("easy", out.get("call"));
        assertEquals("amber", out.get("level"));
        assertEquals(2, out.get("hard_day_streak"));
    }

    @Test
    void freshAndUnderloadedNotesKeySession() {
        // Nothing in the last 7 days -> acute well under chronic (ACWR < 0.8).
        Map<Integer, Double> daily = new HashMap<>();
        for (int a = 8; a <= 42; a++) daily.put(a, 400.0 / 7);
        for (int a = 1; a <= 7; a++) daily.put(a, 200.0 / 7);
        Map<String, Object> out = Readiness.compute(rows(daily, 15.0, 42), List.of(), TODAY);
        assertEquals("hard", out.get("call"));
        assertEquals("green", out.get("level"));
        assertTrue((double) out.get("acwr") < 0.8);
        assertTrue(reasons(out).get(0).contains("key session"));
    }

    @Test
    void staleTsbCannotVetoTheCall() {
        // A series that stopped updating 18 days ago (lapsed sync) must not
        // produce a phantom deep-fatigue call off its last stored TSB of -30.
        Map<Integer, Double> daily = new HashMap<>();
        for (int a = 19; a <= 42; a++) daily.put(a, 400.0 / 7);
        String cutoff = TODAY.minusDays(18).toString();
        List<Map<String, Object>> series = new ArrayList<>();
        for (Map<String, Object> r : rows(daily, -30.0, 42)) {
            if (((String) r.get("date")).compareTo(cutoff) < 0) series.add(r);
        }
        Map<String, Object> out = Readiness.compute(series, List.of(), TODAY);
        assertEquals("ok", out.get("status"));
        assertEquals("hard", out.get("call")); // acwr ~0 and TSB too stale to trust
    }

    @Test
    void todayExcludedFromWindow() {
        // This morning's just-logged ride (today's row) must not flip today's call.
        List<Map<String, Object>> series = new ArrayList<>(flat(400, 0.0, 42));
        Map<String, Object> todayRow = new HashMap<>();
        todayRow.put("date", TODAY.toString());
        todayRow.put("tss", 500.0);
        todayRow.put("ctl", 50.0); todayRow.put("atl", 50.0); todayRow.put("tsb", 0.0);
        series.add(todayRow);
        Map<String, Object> out = Readiness.compute(series, List.of(), TODAY);
        assertEquals(1.0, out.get("acwr"));
    }

    @Test
    void bankerRoundingPct() {
        // Realistic value (matches the "67%" reason string), plus two exact
        // rounding TIES that actually distinguish the mode: 0.125 -> 12.5 rounds
        // DOWN to even 12 (HALF_UP would give 13); 0.375 -> 37.5 rounds UP to
        // even 38 (HALF_DOWN would give 37). Together they pin ties-to-even.
        assertEquals("67%", Py.pct0(40.0 / 60.0));
        assertEquals("12%", Py.pct0(0.125));
        assertEquals("38%", Py.pct0(0.375));
    }

    @Test
    void defaultTodayIsResolvedFromUtcClock() {
        // The null-today default must resolve "today" via a UTC clock so it
        // matches FastAPI (readiness._utcnow), regardless of container tz —
        // otherwise the two backends could disagree on the call near midnight.
        assertEquals(ZoneOffset.UTC, Readiness.clock.getZone(),
                "readiness default clock must be UTC");
        Clock saved = Readiness.clock;
        try {
            // Freeze to 2026-07-16T02:30Z (22:30 Jul-15 in New York). A steady
            // series ending 2026-07-15 is valid history only if "today" resolves
            // to the 16th via the UTC clock, not LocalDate.now() in another zone.
            Readiness.clock = Clock.fixed(Instant.parse("2026-07-16T02:30:00Z"), ZoneOffset.UTC);
            LocalDate anchor = LocalDate.of(2026, 7, 16);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int ago = 42; ago >= 1; ago--) {
                Map<String, Object> r = new HashMap<>();
                r.put("date", anchor.minusDays(ago).toString());
                r.put("tss", 400.0 / 7); r.put("ctl", 50.0);
                r.put("atl", 50.0); r.put("tsb", 0.0);
                rows.add(r);
            }
            Map<String, Object> out = Readiness.compute(rows, List.of(), null);
            assertEquals("ok", out.get("status"));
        } finally {
            Readiness.clock = saved;
        }
    }
}
