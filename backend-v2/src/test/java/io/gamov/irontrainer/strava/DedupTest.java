package io.gamov.irontrainer.strava;

import io.gamov.irontrainer.activity.Activity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mirrors the pure clustering/selection cases in backend/tests/test_dedup.py. */
class DedupTest {

    private static Activity act(long id, String sport, String start, Integer moving) {
        Activity a = new Activity();
        a.id = id; a.sport = sport; a.startDate = start; a.movingTime = moving;
        return a;
    }

    private static Activity dev(long id, String sport, String start, Integer moving,
            String device, Double avgHr, Integer hasPm, Double weightedPower) {
        Activity a = act(id, sport, start, moving);
        a.deviceName = device; a.avgHr = avgHr; a.hasPowerMeter = hasPm; a.weightedPower = weightedPower;
        return a;
    }

    private static Set<Long> ids(List<Activity> cluster) {
        return cluster.stream().map(a -> a.id).collect(Collectors.toSet());
    }

    @Test
    void clusterDetectsSameEventAcrossDevices() {
        Activity a = act(1, "Bike", "2026-06-20T08:00:00", 3600);
        Activity b = act(2, "Bike", "2026-06-20T08:02:00", 3650);
        Activity c = act(3, "Run", "2026-06-21T07:00:00", 1800);
        List<List<Activity>> clusters = Dedup.clusterDuplicates(List.of(a, b, c));
        assertEquals(1, clusters.size());
        assertEquals(Set.of(1L, 2L), ids(clusters.get(0)));
    }

    @Test
    void noClusterForDifferentSportsSameTime() {
        Activity a = act(1, "Bike", "2026-06-20T08:00:00", 3600);
        Activity b = act(2, "Run", "2026-06-20T08:00:00", 3600);
        assertTrue(Dedup.clusterDuplicates(List.of(a, b)).isEmpty());
    }

    @Test
    void cyclingPrefersBikeComputerOverAppleWatch() {
        Activity watch = dev(1, "Bike", "2026-06-20T08:00:00", 3600, "Apple Watch Series 9", 150.0, null, null);
        Activity edge = dev(2, "Bike", "2026-06-20T08:00:00", 3600, "Garmin Edge 1040", 148.0, 1, 210.0);
        assertEquals(2L, Dedup.primaryOf(List.of(watch, edge)).id);
    }

    @Test
    void runningPrefersAppleWatchOverGarmin() {
        Activity watch = dev(1, "Run", "2026-06-20T08:00:00", 3600, "Apple Watch Series 9", 150.0, null, null);
        Activity garmin = dev(2, "Run", "2026-06-20T08:00:00", 3600, "Garmin Forerunner 965", 150.0, null, null);
        assertEquals(1L, Dedup.primaryOf(List.of(garmin, watch)).id);
    }

    @Test
    void fallsBackToDataRichnessWithoutWatch() {
        Activity plain = act(1, "Run", "2026-06-20T08:00:00", 3600);
        Activity rich = dev(2, "Run", "2026-06-20T08:00:00", 3600, null, 150.0, null, null);
        assertEquals(2L, Dedup.primaryOf(List.of(plain, rich)).id);
    }
}
