package io.gamov.irontrainer.athlete;

import io.gamov.irontrainer.activity.Activity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Mirrors app/analysis.py infer_profile. Reference values captured from the
 * Python function; the /api/athlete/infer parity test is the byte-parity gate. */
class AnalysisTest {

    private static Activity act(String sport, String startDate, Integer moving, Double distance,
            Double weightedPower, Double avgPower, Double avgHr, Double maxHr) {
        Activity a = new Activity();
        a.sport = sport;
        a.startDate = startDate;
        a.movingTime = moving;
        a.distance = distance;
        a.weightedPower = weightedPower;
        a.avgPower = avgPower;
        a.avgHr = avgHr;
        a.maxHr = maxHr;
        return a;
    }

    private static String day(LocalDate today, int daysAgo) {
        return today.minusDays(daysAgo) + "T08:00:00Z";
    }

    /** Full reference from FastAPI infer_profile(today=2026-07-01):
     * recent bike (NP 270, hr 155/175), run (10k/3000s=300 pace, hr 162/182),
     * swim (2000m/1800s=90/100m), an out-of-window bike (max_hr 170), and a
     * short bike (<20min, counts only toward availability). */
    @Test
    @SuppressWarnings("unchecked")
    void inferProfileMatchesPythonReference() {
        LocalDate today = LocalDate.of(2026, 7, 1);
        List<Activity> acts = List.of(
                act("Bike", day(today, 5), 3600, 40000.0, 270.0, 250.0, 155.0, 175.0),
                act("Run", day(today, 10), 3000, 10000.0, null, null, 162.0, 182.0),
                act("Swim", day(today, 15), 1800, 2000.0, null, null, null, null),
                act("Bike", day(today, 120), 7200, 60000.0, 300.0, null, 150.0, 170.0),
                act("Bike", day(today, 3), 600, 5000.0, 400.0, null, null, null));

        Map<String, Object> p = Analysis.inferProfile(acts, today);

        assertEquals(256.0, p.get("ftp"));               // round(270*0.95,0) = round(256.5) HALF_EVEN
        assertEquals(162L, p.get("threshold_hr"));       // max avg HR on sustained (155 bike, 162 run)
        assertEquals(182L, p.get("max_hr"));             // observed max — incl. out-of-window activity
        assertEquals(312.0, p.get("threshold_pace_run")); // round(300*1.04,0)
        assertEquals(90.0, p.get("css_swim"));           // round(90,0)
        assertEquals(0.3, p.get("weekly_hours_target")); // round((3600+3000+1800+600)/3600/8, 1)

        Map<String, Object> basis = (Map<String, Object>) p.get("basis");
        assertEquals("95% of best sustained bike NP (270W) in last 12 weeks", basis.get("ftp"));
        assertEquals("fastest sustained run pace (5:00/km) +4%", basis.get("threshold_pace_run"));
        assertEquals("fastest sustained swim pace (1:30/100m)", basis.get("css_swim"));
        assertEquals("max avg HR on sustained efforts (162 bpm)", basis.get("threshold_hr"));
        assertEquals("avg of 2.5h over last 8 weeks", basis.get("weekly_hours_target"));
    }

    /** No qualifying activities → every field null, empty basis (all keys present,
     * matching InferredProfile.as_dict()). */
    @Test
    @SuppressWarnings("unchecked")
    void inferProfileEmptyWhenNoHistory() {
        Map<String, Object> p = Analysis.inferProfile(new ArrayList<>(), LocalDate.of(2026, 7, 1));
        for (String k : List.of("ftp", "threshold_hr", "max_hr", "threshold_pace_run",
                "css_swim", "weekly_hours_target")) {
            assertNull(p.get(k), k + " should be null");
        }
        assertEquals(Map.of(), p.get("basis"));
    }

    /** threshold_hr falls back to ~92% of observed max HR when no sustained effort
     * carried an avg HR. */
    @Test
    void thresholdHrFallsBackToMaxHr() {
        LocalDate today = LocalDate.of(2026, 7, 1);
        // sustained bike with power but NO avg_hr, only max_hr → fallback path
        Map<String, Object> p = Analysis.inferProfile(List.of(
                act("Bike", day(today, 5), 3600, 40000.0, 260.0, null, null, 190.0)), today);
        assertEquals(175L, p.get("threshold_hr"));   // round(190 * 0.92) = round(174.8) = 175
        assertEquals(190L, p.get("max_hr"));
    }

    /** saveInferred fills only blanks and never clears a value inference could not
     * compute (mirrors save_profile with Nones filtered out). */
    @Test
    void saveInferredFillsBlanksOnly() {
        Athlete a = new Athlete();
        a.ftp = 300.0;              // already set — inference below has no ftp, must NOT clear
        Map<String, Object> inferred = new java.util.LinkedHashMap<>();
        inferred.put("ftp", null);              // absent from inference → leave a.ftp
        inferred.put("threshold_hr", 160L);
        inferred.put("css_swim", 92.0);

        Analysis.saveInferred(a, inferred);

        assertEquals(300.0, a.ftp);             // preserved
        assertEquals(160, a.thresholdHr);       // filled
        assertEquals(92.0, a.cssSwim);          // filled
        assertNull(a.thresholdPaceRun);         // untouched
    }
}
