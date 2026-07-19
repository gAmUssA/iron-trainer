package io.gamov.irontrainer.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** sanitize_feel + _feel_vs_data_line — byte-parity strings + clamping. */
class CheckinsTest {

    @Test
    void sanitizeFeelClampsAndFilters() {
        assertNull(Checkins.sanitizeFeel(null));
        assertNull(Checkins.sanitizeFeel(Map.of("foo", 3)));       // no valid keys → null

        Map<String, Object> in = new LinkedHashMap<>();
        in.put("energy", 5);
        in.put("sleep", 2);
        in.put("body", 7);     // clamp to 5
        in.put("stress", 0);   // clamp to 1
        in.put("note", "  feeling ok  ");
        Map<String, Object> out = Checkins.sanitizeFeel(in);
        assertEquals(5, out.get("energy"));
        assertEquals(2, out.get("sleep"));
        assertEquals(5, out.get("body"));
        assertEquals(1, out.get("stress"));
        assertEquals("feeling ok", out.get("note"));   // trimmed
    }

    @Test
    void feelVsDataNullWhenNoScoresOrNotOk() {
        Map<String, Object> ok = Map.of("status", "ok", "level", "green");
        assertNull(Checkins.feelVsDataLine(null, ok));                       // no scores
        assertNull(Checkins.feelVsDataLine(Map.of("energy", 3),
                Map.of("status", "insufficient_data")));                    // not ok
    }

    @Test
    void feelVsDataLowFeelGreen() {
        // avg 2.0, level green → the "steady load / life stress" line.
        String line = Checkins.feelVsDataLine(Map.of("energy", 2, "sleep", 2),
                Map.of("status", "ok", "level", "green"));
        assertTrue(line.startsWith("Feel vs data: you rate the week 2.0/5 but your training load is steady"), line);
    }

    @Test
    void feelVsDataHighFeelAmber() {
        // avg 4.0, level amber → the "you feel good but numbers disagree — <reason>" line.
        String line = Checkins.feelVsDataLine(Map.of("energy", 4, "sleep", 4),
                Map.of("status", "ok", "level", "amber", "reasons", List.of("HRV suppressed.")));
        assertEquals("Feel vs data: you feel good (4.0/5) but the numbers disagree — "
                + "HRV suppressed. Don't let a good day bait an overreach.", line);
    }

    @Test
    void feelVsDataAligned() {
        String line = Checkins.feelVsDataLine(Map.of("energy", 3, "sleep", 3),
                Map.of("status", "ok", "level", "green"));
        assertEquals("Feel vs data: aligned (3.0/5 vs a green load picture).", line);
    }
}
