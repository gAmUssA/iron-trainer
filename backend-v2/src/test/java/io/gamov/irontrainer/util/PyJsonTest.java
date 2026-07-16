package io.gamov.irontrainer.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PyJson must match Python's json.dumps + datetime.isoformat byte-for-byte, so
 * blobs/timestamps written to the shared DB are identical to FastAPI's. */
class PyJsonTest {

    @Test
    void dumpsMatchesJsonDumpsSpacing() {
        // json.dumps default: ": " after key, ", " between items — not compact.
        assertEquals("{\"ftp\": 238}", PyJson.dumps(Map.of("ftp", 238)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("avg_power_w", 250);
        m.put("note", "ok");
        assertEquals("{\"avg_power_w\": 250, \"note\": \"ok\"}", PyJson.dumps(m));
        assertEquals("[1, 2, 3]", PyJson.dumps(List.of(1, 2, 3)));
        // Nested (a workout step): array + object spacing together.
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("type", "warmup");
        step.put("duration_s", 900);
        assertEquals("[{\"type\": \"warmup\", \"duration_s\": 900}]",
                PyJson.dumps(List.of(step)));
    }

    @Test
    void utcNowIsoFormat() {
        String s = PyJson.utcNowIso();
        // Python isoformat: microsecond precision + explicit +00:00 (never 'Z').
        assertTrue(s.endsWith("+00:00"), s);
        assertTrue(s.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}\\+00:00"), s);
    }
}
