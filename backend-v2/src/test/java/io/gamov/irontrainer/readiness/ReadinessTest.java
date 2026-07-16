package io.gamov.irontrainer.readiness;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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

    @Test
    void recoveryFlagDowngradesGreenToEasy() {
        List<Map<String, Object>> rec = new ArrayList<>();
        for (int ago = 10; ago >= 1; ago--) {
            Map<String, Object> r = new HashMap<>();
            r.put("date", TODAY.minusDays(ago).toString());
            r.put("sleep_h", 7.5); r.put("hrv_ms", 60.0); r.put("rhr_bpm", 46.0);
            rec.add(r);
        }
        Map<String, Object> latest = new HashMap<>();
        latest.put("date", TODAY.toString());
        latest.put("sleep_h", 7.5); latest.put("hrv_ms", 40.0); latest.put("rhr_bpm", 46.0);
        rec.add(latest);
        Map<String, Object> out = Readiness.compute(flat(400, 0.0, 42), rec, TODAY);
        assertEquals("easy", out.get("call"));
        assertTrue(reasons(out).get(0).contains("HRV suppressed"));
    }

    @Test
    void bankerRoundingPct() {
        assertEquals("67%", Py.pct0(40.0 / 60.0));
    }
}
