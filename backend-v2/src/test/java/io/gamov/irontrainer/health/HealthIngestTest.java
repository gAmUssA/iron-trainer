package io.gamov.irontrainer.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Parser parity for Health Auto Export payloads (HealthIngest.parsePayload):
 * offset-aware local-day keying, unit conversions (lb→kg, °F→°C), same-day
 * averaging, and the sleep-hours resolution order. */
class HealthIngestTest {

    @Test
    void parsesMetricsSleepUnitsAndBadDates() {
        Map<String, Object> payload = Map.of("data", Map.of("metrics", List.of(
                metric("heart_rate_variability", "ms", List.of(
                        rec("date", "2026-07-13 07:00:00 -0400", 55.0),
                        rec("date", "2026-07-13 08:00:00 -0400", 65.0))),   // avg → 60
                metric("resting_heart_rate", "bpm", List.of(
                        rec("date", "2026-07-13 06:00:00 -0400", 48.0))),
                metric("weight_body_mass", "lb", List.of(
                        rec("date", "2026-07-13 06:00:00 -0400", 154.0))),  // → 69.85 kg
                metric("apple_sleeping_wrist_temperature", "degF", List.of(
                        rec("date", "2026-07-13 03:00:00 -0400", 98.6))),   // → 37.0 C
                Map.of("name", "sleep_analysis", "data", List.of(Map.of(
                        "sleepEnd", "2026-07-13 06:30:00 -0400",
                        "sleepStart", "2026-07-12 23:00:00 -0400",
                        "core", 4.0, "deep", 1.5, "rem", 1.0, "awake", 0.3))),
                metric("some_unknown_metric", "x", List.of(
                        rec("date", "2026-07-13 06:00:00 -0400", 1.0))),
                metric("resting_heart_rate", "bpm", List.of(
                        rec("date", "garbage-date", 50.0))))));           // bad date

        HealthIngest.Result r = HealthIngest.parsePayload(payload);

        assertEquals(8, r.records);
        assertEquals(1, r.badDates);
        assertEquals(List.of("some_unknown_metric"), r.unknownMetrics);

        Map<String, Object> day = r.days.get("2026-07-13");
        assertTrue(day != null, "day keyed by the offset-local date");
        assertEquals(60.0, day.get("hrv_ms"));
        assertEquals(48.0, day.get("rhr_bpm"));
        assertEquals(69.85, day.get("weight_kg"));         // round(154 * 0.45359237, 2)
        assertEquals(37.0, day.get("wrist_temp_c"));       // round((98.6-32)*5/9, 2)
        assertEquals(6.5, day.get("sleep_h"));             // core+deep+rem
        assertEquals(1.5, day.get("deep_h"));
        assertEquals(1.0, day.get("rem_h"));
        assertEquals(0.3, day.get("awake_h"));
        assertEquals("2026-07-12T23:00:00-04:00", day.get("sleep_start"));
        assertEquals("2026-07-13T06:30:00-04:00", day.get("sleep_end"));
    }

    @Test
    void sleepFallsBackToTotalThenAsleep() {
        // totalSleep wins over stages.
        HealthIngest.Result r = HealthIngest.parsePayload(Map.of("data", Map.of("metrics", List.of(
                Map.of("name", "sleep_analysis", "data", List.of(Map.of(
                        "sleepEnd", "2026-07-13 06:00:00 -0400",
                        "totalSleep", 7.25, "core", 4.0)))))));
        assertEquals(7.25, r.days.get("2026-07-13").get("sleep_h"));
    }

    private static Map<String, Object> metric(String name, String units, List<Object> data) {
        return Map.of("name", name, "units", units, "data", data);
    }

    private static Map<String, Object> rec(String dateKey, String date, double qty) {
        return Map.of(dateKey, date, "qty", qty);
    }
}
