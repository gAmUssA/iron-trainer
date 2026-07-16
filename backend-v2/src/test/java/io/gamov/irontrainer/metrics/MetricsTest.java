package io.gamov.irontrainer.metrics;

import io.gamov.irontrainer.metrics.Metrics.DayMetric;
import io.gamov.irontrainer.metrics.Metrics.Thresholds;
import io.gamov.irontrainer.metrics.Metrics.TssResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mirrors backend/tests/test_metrics.py — the Python suite is the spec for the
 * TSS + PMC math the metrics-write path stores. */
class MetricsTest {

    @Test
    void normalizeSport() {
        assertEquals("Bike", Metrics.normalizeSport("VirtualRide"));
        assertEquals("Run", Metrics.normalizeSport("TrailRun"));
        assertEquals("Swim", Metrics.normalizeSport("Swim"));
        assertEquals("Other", Metrics.normalizeSport("WeightTraining"));
        assertEquals("Other", Metrics.normalizeSport(null));
    }

    @Test
    void powerTssOneHourAtFtpIs100() {
        Thresholds th = new Thresholds(250.0, null, null, null, null);
        TssResult r = Metrics.computeTss("Bike", 3600.0, 40000.0, 250.0, 240.0, 150.0, th);
        assertEquals("power", r.method());
        assertEquals(1.0, r.intensityFactor());
        assertEquals(100.0, r.tss());
    }

    @Test
    void runPaceTssAtThresholdIs100() {
        // Threshold pace 300 s/km; run exactly at that pace for 1h → IF 1.0.
        Thresholds th = new Thresholds(null, null, null, 300.0, null);
        TssResult r = Metrics.computeTss("Run", 3600.0, 12000.0, null, null, 160.0, th);
        assertEquals("pace", r.method());
        assertEquals(1.0, r.intensityFactor());
        assertEquals(100.0, r.tss());
    }

    @Test
    void durationFallbackUsesDefaultIf() {
        TssResult r = Metrics.computeTss("Other", 3600.0, null, null, null, null,
                new Thresholds(null, null, null, null, null));
        assertEquals("duration", r.method());
        assertEquals(0.7, r.intensityFactor());
        assertEquals(49.0, r.tss());  // 1h * 0.7^2 * 100
    }

    @Test
    void zeroDurationIsZeroTss() {
        TssResult r = Metrics.computeTss("Run", 0.0, 0.0, null, null, null,
                new Thresholds(null, null, null, null, null));
        assertEquals(0.0, r.tss());
    }

    @Test
    void pmcSingleDayIncrementsCtlByTssOver42() {
        LocalDate day = LocalDate.of(2026, 6, 1);
        List<DayMetric> s = Metrics.performanceManagement(
                List.of(Map.entry(day, 100.0)), day, null, 0.0, 0.0);
        assertEquals(1, s.size());
        assertEquals(2.4, s.get(0).ctl());   // round(100/42, 1)
        assertEquals(14.3, s.get(0).atl());  // round(100/7, 1)
        assertEquals(0.0, s.get(0).tsb());   // form uses yesterday's (seed) values
    }

    @Test
    void pmcFillsRestDaysWithZeroTss() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        LocalDate end = LocalDate.of(2026, 6, 5);
        List<DayMetric> s = Metrics.performanceManagement(
                List.of(Map.entry(start, 100.0)), end, start, 0.0, 0.0);
        assertEquals(List.of(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 3),
                LocalDate.of(2026, 6, 4), LocalDate.of(2026, 6, 5)),
                s.stream().map(DayMetric::day).toList());
        assertEquals(0.0, s.get(1).tss());
        assertTrue(s.get(1).ctl() < s.get(0).ctl() + 0.001);  // decays after the stimulus
    }
}
