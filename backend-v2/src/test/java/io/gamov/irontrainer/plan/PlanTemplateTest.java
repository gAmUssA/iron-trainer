package io.gamov.irontrainer.plan;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Byte-parity pins for the deterministic engine (template.build_season /
 * expand_week + validator). Values computed from the Python reference. */
class PlanTemplateTest {

    @Test
    @SuppressWarnings("unchecked")
    void buildSeasonSkeleton() {
        // Mon 2026-03-02 → race Sat 2026-09-26. race_monday = 2026-09-21.
        Map<String, Object> season = PlanTemplate.buildSeason(
                LocalDate.parse("2026-03-02"), LocalDate.parse("2026-09-26"), 8.0);
        List<Map<String, Object>> weeks = (List<Map<String, Object>>) season.get("weeks");

        assertEquals("2026-03-02", weeks.get(0).get("week_start"));   // monday_of(start)
        assertEquals("2026-03-02", season.get("start_date"));
        assertEquals("IRONMAN 70.3", season.get("race_name"));
        int n = weeks.size();
        assertTrue(n >= 4);
        // last 2 weeks = taper; week 4 (i=3) = recovery (i%4==3, not taper).
        assertEquals("taper", weeks.get(n - 1).get("phase"));
        assertEquals("taper", weeks.get(n - 2).get("phase"));
        assertEquals(true, weeks.get(3).get("is_recovery"));
        // week 1: base, hours = 8.0 (build_index 0 → 8*1.06^0), target_tss = round(8*60,0)=480.0
        assertEquals("base", weeks.get(0).get("phase"));
        assertEquals(8.0, ((Number) weeks.get(0).get("target_hours")).doubleValue(), 1e-9);
        assertEquals(480.0, ((Number) weeks.get(0).get("target_tss")).doubleValue(), 1e-9);
        assertTrue(((String) season.get("summary")).startsWith(n + "-week 70.3 build:"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void expandWeekBikeThresholdWithPower() {
        Map<String, Object> week = Map.of(
                "week_start", "2026-03-02", "target_hours", 8.0, "is_recovery", false, "phase", "build");
        Map<String, Object> profile = Map.of(
                "ftp", 250.0, "threshold_hr", 160, "max_hr", 185, "threshold_pace_run", 300.0, "css_swim", 95.0);

        List<Map<String, Object>> wos = PlanTemplate.expandWeek(week, profile);
        assertEquals(6, wos.size());

        Map<String, Object> bike = wos.get(1);   // ("Bike", 1, bike_h*0.30, threshold, "Bike intervals")
        assertEquals("Bike", bike.get("sport"));
        assertEquals("threshold", bike.get("intensity"));
        assertEquals(4320, ((Number) bike.get("duration_s")).intValue());   // int(8*0.5*0.30*3600)
        assertEquals(115.2, ((Number) bike.get("planned_tss")).doubleValue(), 1e-9);
        assertTrue(((String) bike.get("description")).contains("Z4 · HR 150–158 bpm"), bike.get("description").toString());

        List<Map<String, Object>> steps = (List<Map<String, Object>>) bike.get("steps");
        assertEquals(3, steps.size());
        Map<String, Object> steady = (Map<String, Object>) steps.get(1).get("target");
        assertEquals("power", steady.get("type"));
        assertEquals("W", steady.get("unit"));
        assertEquals(238L, steady.get("low"));    // round(250*0.95)=round(237.5) HALF_EVEN → 238
        assertEquals(262L, steady.get("high"));   // round(250*1.05)=round(262.5) HALF_EVEN → 262
        Map<String, Object> warm = (Map<String, Object>) steps.get(0).get("target");
        assertEquals(162L, warm.get("low"));       // endurance round(250*0.65)=round(162.5) → 162
        assertEquals(188L, warm.get("high"));      // round(250*0.75)=round(187.5) → 188
    }

    @Test
    @SuppressWarnings("unchecked")
    void expandWeekRunNoThresholdFallsBackToHr() {
        // No threshold_pace_run → run prescribed by HR zones; distance null.
        Map<String, Object> week = Map.of(
                "week_start", "2026-03-02", "target_hours", 6.0, "is_recovery", false, "phase", "base");
        Map<String, Object> profile = Map.of("threshold_hr", 160, "max_hr", 185);
        List<Map<String, Object>> wos = PlanTemplate.expandWeek(week, profile);
        Map<String, Object> run = wos.get(2);   // Run quality
        assertEquals("Run", run.get("sport"));
        assertEquals(null, run.get("distance_m"));   // no pace → no estimate
        List<Map<String, Object>> steps = (List<Map<String, Object>>) run.get("steps");
        assertEquals("hr", ((Map<String, Object>) steps.get(1).get("target")).get("type"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void validatorTapersAndCapsRamp() {
        // Two build weeks with a >10% jump, then something → ramp cap note.
        Map<String, Object> w1 = new java.util.LinkedHashMap<>(Map.of(
                "week_index", 1, "week_start", "2026-03-02", "phase", "build", "is_recovery", false, "target_hours", 8.0));
        Map<String, Object> w2 = new java.util.LinkedHashMap<>(Map.of(
                "week_index", 2, "week_start", "2026-03-09", "phase", "build", "is_recovery", false, "target_hours", 12.0));
        Map<String, Object> w3 = new java.util.LinkedHashMap<>(Map.of(
                "week_index", 3, "week_start", "2026-03-16", "phase", "build", "is_recovery", false, "target_hours", 10.0));
        Map<String, Object> w4 = new java.util.LinkedHashMap<>(Map.of(
                "week_index", 4, "week_start", "2026-03-23", "phase", "build", "is_recovery", false, "target_hours", 10.0));
        Map<String, Object> season = new java.util.LinkedHashMap<>();
        season.put("weeks", List.of(w1, w2, w3, w4));

        PlanValidator.Result r = PlanValidator.validateSeason(season);
        List<Map<String, Object>> weeks = (List<Map<String, Object>>) r.season().get("weeks");
        // last 2 → taper
        assertEquals("taper", weeks.get(2).get("phase"));
        assertEquals("taper", weeks.get(3).get("phase"));
        // week 2 ramp capped 8.0*1.10 = 8.8 (was 12.0)
        assertEquals(8.8, ((Number) weeks.get(1).get("target_hours")).doubleValue(), 1e-9);
        assertTrue(r.notes().stream().anyMatch(x -> x.contains("Week 2: ramp capped to 8.8h (was 12.0h, +10% max).")),
                r.notes().toString());
        assertFalse(r.notes().isEmpty());
    }
}
