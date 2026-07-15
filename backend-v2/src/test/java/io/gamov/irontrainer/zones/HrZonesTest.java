package io.gamov.irontrainer.zones;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Mirrors backend/tests/test_zones.py — the Python suite is the spec. */
class HrZonesTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> zone(Map<String, Object> table, String key) {
        return ((List<Map<String, Object>>) table.get("zones")).stream()
                .filter(z -> z.get("zone").equals(key)).findFirst().orElseThrow();
    }

    @Test
    void lthrBands() {
        Map<String, Object> t = HrZones.hrZones(160, null);
        assertEquals("lthr", t.get("basis"));
        assertEquals((long) Math.rint(160 * 0.81), zone(t, "Z2").get("low"));
        assertEquals((long) Math.rint(160 * 0.89), zone(t, "Z2").get("high"));
        assertEquals((long) Math.rint(160 * 0.94), zone(t, "Z4").get("low"));
        assertEquals((long) Math.rint(160 * 0.99), zone(t, "Z4").get("high"));
    }

    @Test
    void bankerRoundingOnTies() {
        // threshold_hr=45, Z3 low = 45*0.90 = 40.5 exactly. Python round()
        // (banker's, ties-to-even) → 40, NOT half-up 41. Parity requires
        // Math.rint, not Math.round (Copilot review, PR #39).
        Map<String, Object> t = HrZones.hrZones(45, null);
        assertEquals(40L, zone(t, "Z3").get("low"));
    }

    @Test
    void zone5CappedByMaxHr() {
        Map<String, Object> t = HrZones.hrZones(160, 165);
        assertEquals(165L, zone(t, "Z5").get("high")); // 160*1.06=170 would exceed
    }

    @Test
    void maxHrFallbackBasis() {
        Map<String, Object> t = HrZones.hrZones(null, 180);
        assertEquals("max_hr", t.get("basis"));
        assertEquals(108L, zone(t, "Z2").get("low"));
        assertEquals(126L, zone(t, "Z2").get("high"));
        Map<String, Object> none = HrZones.hrZones(null, null);
        assertEquals(null, none.get("basis"));
        assertEquals(List.of(), none.get("zones"));
    }
}
