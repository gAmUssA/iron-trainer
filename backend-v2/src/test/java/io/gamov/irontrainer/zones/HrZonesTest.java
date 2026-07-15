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
        assertEquals(Math.round(160 * 0.81), zone(t, "Z2").get("low"));
        assertEquals(Math.round(160 * 0.89), zone(t, "Z2").get("high"));
        assertEquals(Math.round(160 * 0.94), zone(t, "Z4").get("low"));
        assertEquals(Math.round(160 * 0.99), zone(t, "Z4").get("high"));
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
