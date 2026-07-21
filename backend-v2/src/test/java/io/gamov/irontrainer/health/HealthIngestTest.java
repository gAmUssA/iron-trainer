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

    @Test
    void lenientDatesTruthyFallbackAndBooleans() {
        Map<String, Object> payload = Map.of("data", Map.of("metrics", List.of(
                // colon offset with a space separator
                metric("resting_heart_rate", "bpm", List.of(
                        rec("date", "2026-07-14 06:00:00 -04:00", 50.0))),
                // empty date falls through to startDate (Python `or`)
                Map.of("name", "vo2max", "data", List.of(Map.of(
                        "date", "", "startDate", "2026-07-14 06:00:00 -0400", "qty", 55.0))),
                // boolean qty coerces to 1.0 (Python bool is an int subclass)
                Map.of("name", "respiratory_rate", "data", List.of(Map.of(
                        "date", "2026-07-14 06:00:00 -0400", "qty", true))),
                // naive (offset-less) ISO date
                metric("weight_body_mass", "kg", List.of(
                        rec("date", "2026-07-14T06:00:00", 70.0))))));

        HealthIngest.Result r = HealthIngest.parsePayload(payload);
        assertEquals(0, r.badDates);
        Map<String, Object> day = r.days.get("2026-07-14");
        assertEquals(50.0, day.get("rhr_bpm"));          // colon offset parsed
        assertEquals(55.0, day.get("vo2max"));           // empty date → startDate fallback
        assertEquals(1.0, day.get("respiratory_rate"));  // boolean true → 1.0
        assertEquals(70.0, day.get("weight_kg"));        // naive ISO date
    }

    @Test
    void parsesExpandedHaeMetricsSumAvgAndConversions() {
        Map<String, Object> payload = Map.of("data", Map.of("metrics", List.of(
                // gauge → averaged
                metric("cardio_recovery", "bpm", List.of(
                        rec("date", "2026-07-15 09:00:00 -0400", 30.0),
                        rec("date", "2026-07-15 10:00:00 -0400", 40.0))),   // avg → 35
                // SpO2 as a 0–1 fraction → percent
                metric("blood_oxygen_saturation", "%", List.of(
                        rec("date", "2026-07-15 03:00:00 -0400", 0.97))),   // → 97.0
                // cumulative → summed; kJ → kcal per sample first
                metric("active_energy", "kJ", List.of(
                        rec("date", "2026-07-15 08:00:00 -0400", 418.4),
                        rec("date", "2026-07-15 12:00:00 -0400", 418.4))),  // (100+100) → 200
                metric("apple_exercise_time", "min", List.of(
                        rec("date", "2026-07-15 08:00:00 -0400", 20.0),
                        rec("date", "2026-07-15 18:00:00 -0400", 25.0))),   // sum → 45
                metric("step_count", "count", List.of(
                        rec("date", "2026-07-15 08:00:00 -0400", 4000.0),
                        rec("date", "2026-07-15 18:00:00 -0400", 4452.0))), // sum → 8452
                metric("cycling_functional_threshold_power", "W", List.of(
                        rec("date", "2026-07-15 08:00:00 -0400", 250.0))))));

        HealthIngest.Result r = HealthIngest.parsePayload(payload);
        Map<String, Object> day = r.days.get("2026-07-15");
        assertTrue(day != null, "day keyed by offset-local date");
        assertEquals(35.0, day.get("hr_recovery_bpm"));      // gauge → averaged
        assertEquals(97.0, day.get("spo2_pct"));             // 0.97 fraction → percent
        assertEquals(200.0, day.get("active_energy_kcal"));  // kJ→kcal, then summed
        assertEquals(45.0, day.get("exercise_min"));         // cumulative → summed
        assertEquals(8452.0, day.get("step_count"));         // cumulative → summed
        assertEquals(250.0, day.get("cycling_ftp_w"));
    }

    private static Map<String, Object> metric(String name, String units, List<Object> data) {
        return Map.of("name", name, "units", units, "data", data);
    }

    private static Map<String, Object> rec(String dateKey, String date, double qty) {
        return Map.of(dateKey, date, "qty", qty);
    }
}
