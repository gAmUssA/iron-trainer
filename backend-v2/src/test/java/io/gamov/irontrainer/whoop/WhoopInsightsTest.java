package io.gamov.irontrainer.whoop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Deterministic insights: behavior deltas (min-days gate, ranking), circular
 * bedtime std-dev (midnight wrap), and the 28-day trend windows. */
class WhoopInsightsTest {

    private static WhoopCycle cycle(String date, Double rec, Double hrv, Double strain) {
        WhoopCycle c = new WhoopCycle();
        c.date = date;
        c.recoveryScore = rec;
        c.hrvRmssdMs = hrv;
        c.dayStrain = strain;
        return c;
    }

    private static WhoopJournalEntry entry(String date, String q, boolean yes) {
        WhoopJournalEntry j = new WhoopJournalEntry();
        j.date = date;
        j.question = q;
        j.answeredYes = yes;
        return j;
    }

    @Test
    void behaviorDeltasGatedAndRanked() {
        List<WhoopCycle> cycles = new ArrayList<>();
        List<WhoopJournalEntry> journal = new ArrayList<>();
        LocalDate d0 = LocalDate.parse("2026-06-01");
        // 10 alcohol-yes days at recovery 40, 10 no days at 70 → delta -30.
        // "Rare?" only has 2 yes days → gated out.
        for (int i = 0; i < 20; i++) {
            String date = d0.plusDays(i).toString();
            boolean yes = i < 10;
            cycles.add(cycle(date, yes ? 40.0 : 70.0, yes ? 30.0 : 50.0, 10.0));
            journal.add(entry(date, "Have any alcoholic drinks?", yes));
            if (i < 2) {
                journal.add(entry(date, "Rare?", true));
            }
        }
        List<Map<String, Object>> rows =
                WhoopInsights.behaviors(byDate(cycles), journal);
        assertEquals(1, rows.size());
        Map<String, Object> r = rows.get(0);
        assertEquals("Have any alcoholic drinks?", r.get("question"));
        assertEquals(10, r.get("yes_days"));
        assertEquals(-30.0, r.get("recovery_delta"));
        assertEquals(-20.0, r.get("hrv_delta"));
    }

    @Test
    void circularStdHandlesMidnightWrap() {
        // 23:30 and 00:30 are 60 min apart around midnight: std ≈ 30 min, and a
        // naive linear std (~11.5 h) would be catastrophically wrong.
        Double std = WhoopInsights.circularStdMinutes(List.of(23.5 * 60, 0.5 * 60));
        assertTrue(std > 25 && std < 35, "got " + std);
        assertEquals(0.0, WhoopInsights.circularStdMinutes(List.of(600.0, 600.0)));
        assertNull(WhoopInsights.circularStdMinutes(List.of(600.0)));
    }

    @Test
    void trendComparesLast28ToPrevious28() {
        List<WhoopCycle> cycles = new ArrayList<>();
        LocalDate anchor = LocalDate.parse("2026-07-29");
        // Previous 28 days: strain 8; last 28 days: strain 14.
        for (int i = 0; i < 56; i++) {
            String date = anchor.minusDays(i).toString();
            cycles.add(cycle(date, 60.0, 40.0, i < 28 ? 14.0 : 8.0));
        }
        Map<String, Object> t = WhoopInsights.trend(byDate(cycles), anchor);
        assertEquals(14.0, t.get("strain_28d"));
        assertEquals(8.0, t.get("strain_prev_28d"));
    }

    @Test
    void computeAnchorsOnNewestDataDate() {
        Map<String, Object> out = WhoopInsights.compute(
                List.of(cycle("2026-07-01", 60.0, 40.0, 10.0)), List.of());
        assertEquals("2026-07-01", out.get("anchor_date"));
        assertEquals(1, out.get("days"));
    }

    private static java.util.TreeMap<String, WhoopCycle> byDate(List<WhoopCycle> cycles) {
        java.util.TreeMap<String, WhoopCycle> m = new java.util.TreeMap<>();
        for (WhoopCycle c : cycles) {
            m.put(c.date, c);
        }
        return m;
    }
}
