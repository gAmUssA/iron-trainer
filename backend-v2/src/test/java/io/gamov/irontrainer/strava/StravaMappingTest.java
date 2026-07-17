package io.gamov.irontrainer.strava;

import io.gamov.irontrainer.metrics.Metrics;
import io.gamov.irontrainer.metrics.Metrics.Thresholds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** _map_activity (raw Strava → activity row) + normalized_power. */
class StravaMappingTest {

    @Test
    void mapActivityBikePower() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", 12345L);
        raw.put("type", "Ride");
        raw.put("sport_type", "Ride");
        raw.put("start_date_local", "2026-07-10T08:00:00");
        raw.put("start_date", "2026-07-10T15:00:00");  // UTC — should NOT win over local
        raw.put("name", "Morning Ride");
        raw.put("moving_time", 3600);
        raw.put("elapsed_time", 3700);
        raw.put("distance", 40000.0);
        raw.put("weighted_average_watts", 250);
        raw.put("average_watts", 240);
        raw.put("average_heartrate", 150);
        raw.put("device_watts", true);

        Map<String, Object> a = StravaMapping.mapActivity(raw, new Thresholds(250.0, null, null, null, null), 7);
        assertEquals("Bike", a.get("sport"));
        assertEquals("2026-07-10T08:00:00", a.get("start_date"));  // prefers start_date_local
        assertEquals(7, a.get("athlete_id"));
        assertEquals(1, a.get("has_power_meter"));
        assertEquals(250.0, a.get("weighted_power"));
        assertEquals(100.0, a.get("tss"));            // 1h at FTP
        assertEquals(1.0, a.get("intensity_factor"));
        assertEquals("power", a.get("tss_method"));
    }

    @Test
    void mapActivityUnknownSportFallsBackToOther() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", 1L);
        raw.put("type", "WeightTraining");
        raw.put("moving_time", 3600);
        Map<String, Object> a = StravaMapping.mapActivity(raw, new Thresholds(null, null, null, null, null), 1);
        assertEquals("Other", a.get("sport"));
        assertEquals("duration", a.get("tss_method"));
        assertEquals(49.0, a.get("tss"));  // 1h * 0.7^2 * 100
    }

    @Test
    void normalizedPowerConstantStream() {
        List<Double> flat = new ArrayList<>(Collections.nCopies(30, 200.0));
        assertEquals(200L, Metrics.normalizedPower(flat, 1.0));
        // Fewer than 30 samples → not enough data.
        assertNull(Metrics.normalizedPower(new ArrayList<>(Collections.nCopies(29, 200.0)), 1.0));
    }
}
