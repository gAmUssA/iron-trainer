package io.gamov.irontrainer.tests;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The catalog is fixed data; endpoint byte-parity is the gate. These pin the
 * shape/content so a drift from fitness_tests.py is caught locally. */
class FitnessTestsTest {

    @Test
    void retestWindow() {
        assertEquals(35, FitnessTests.RETEST_DAYS);
    }

    @Test
    void catalogShapeAndOrder() {
        List<Map<String, Object>> cat = FitnessTests.catalog();
        assertEquals(3, cat.size());
        assertEquals(List.of("bike-ftp-20", "run-lthr-30", "swim-css-400-200"),
                cat.stream().map(t -> t.get("slug")).toList());
        // Key order matters for JSON parity.
        assertEquals(List.of("slug", "name", "sport", "measures", "description",
                "inputs", "prefill_sport"),
                List.copyOf(cat.get(0).keySet()));
        Map<String, Object> bike = cat.get(0);
        assertEquals("Bike", bike.get("sport"));
        assertEquals(List.of("ftp"), bike.get("measures"));
        assertEquals("Bike", bike.get("prefill_sport"));
    }

    @Test
    void swimHasNoPrefillSport() {
        assertNull(FitnessTests.get("swim-css-400-200").get("prefill_sport"));
    }

    @Test
    void getUnknownIsNull() {
        assertNull(FitnessTests.get("nope"));
        assertTrue(FitnessTests.get("run-lthr-30") != null);
    }

    @Test
    void computeThresholds() {
        assertEquals(Map.of("ftp", 238L),
                FitnessTests.compute("bike-ftp-20", Map.of("avg_power_w", 250)));
        Map<String, Object> run = FitnessTests.compute("run-lthr-30",
                Map.of("distance_m", 6000, "time_s", 1800, "avg_hr_last20", 160));
        assertEquals(160L, run.get("threshold_hr"));
        assertEquals(300L, run.get("threshold_pace_run"));
        assertEquals(List.of("threshold_hr", "threshold_pace_run"), List.copyOf(run.keySet()));
        assertEquals(Map.of("css_swim", 95L),
                FitnessTests.compute("swim-css-400-200", Map.of("t400_s", 360, "t200_s", 170)));
    }

    @Test
    void computeSkipsPaceWhenNoDistance() {
        // Python: threshold_pace_run only when distance_m > 0.
        Map<String, Object> run = FitnessTests.compute("run-lthr-30",
                Map.of("distance_m", 0, "time_s", 1800, "avg_hr_last20", 160));
        assertEquals(Map.of("threshold_hr", 160L), run);
    }

    @Test
    void computeMissingInputThrows() {
        assertThrows(FitnessTests.BadInput.class,
                () -> FitnessTests.compute("bike-ftp-20", Map.of()));  // no avg_power_w
    }

    @Test
    @SuppressWarnings("unchecked")
    void toWorkoutShape() {
        Map<String, Object> w = FitnessTests.toWorkout("bike-ftp-20");
        assertEquals("Bike", w.get("sport"));
        assertEquals("Bike — 20-min FTP (test)", w.get("title"));
        assertEquals("test", w.get("intensity"));
        assertEquals(2700, w.get("duration_s"));  // 900 + 1200 + 600
        assertEquals(List.of("sport", "title", "description", "intensity", "steps", "duration_s"),
                List.copyOf(w.keySet()));
        List<Map<String, Object>> steps = (List<Map<String, Object>>) w.get("steps");
        assertEquals(3, steps.size());
        assertEquals("warmup", steps.get(0).get("type"));
        assertEquals("interval", steps.get(1).get("type"));
        assertEquals(1200, steps.get(1).get("duration_s"));
        List<Map<String, Object>> swim =
                (List<Map<String, Object>>) FitnessTests.toWorkout("swim-css-400-200").get("steps");
        assertEquals(5, swim.size());
        assertEquals(400, swim.get(1).get("distance_m"));
    }

    @Test
    void emptyOrNullJsonBlobsBecomeEmptyMaps() {
        // Python json.loads(x or "{}"): both null and "" are falsy → {}, not a
        // 500. (A real row should never hold "", but the port must be faithful.)
        FitnessTestResult r = new FitnessTestResult();
        r.id = 1; r.athleteId = 1; r.testSlug = "bike-ftp-20"; r.sport = "Bike";
        r.date = "2026-06-01"; r.applied = false; r.createdAt = "2026-06-01T10:00:00+00:00";
        r.inputsJson = "";       // empty string
        r.resultJson = null;     // null
        Map<String, Object> row = r.toRow();
        assertEquals(Map.of(), row.get("inputs"));
        assertEquals(Map.of(), row.get("result"));
    }
}
