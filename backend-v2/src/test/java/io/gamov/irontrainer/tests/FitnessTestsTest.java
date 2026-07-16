package io.gamov.irontrainer.tests;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
