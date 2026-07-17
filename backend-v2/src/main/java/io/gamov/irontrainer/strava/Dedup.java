package io.gamov.irontrainer.strava;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.util.Iso;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Detect duplicate activities — the same workout captured by more than one
 * device. Faithful port of app/dedup.py. Pure logic over Activity rows: cluster
 * same-event same-sport activities, then pick which one to keep (cycling →
 * bike computer, else Apple Watch, then richer data, then longest). */
public final class Dedup {

    private Dedup() {}

    static final long START_TOLERANCE_S = 20 * 60;
    static final double DURATION_RATIO_MIN = 0.5;
    private static final String[] BIKE_COMPUTERS =
            {"edge", "elemnt", "wahoo", "bolt", "roam", "karoo", "hammerhead"};

    /** start_date → naive wall-clock (tz stripped, like _start + replace(tzinfo=None)). */
    private static LocalDateTime start(Activity a) {
        return Iso.parseDateTime(a.startDate);
    }

    static boolean isAppleWatch(String deviceName) {
        return deviceName != null && !deviceName.isEmpty()
                && deviceName.toLowerCase().contains("apple watch");
    }

    static boolean isBikeComputer(String deviceName) {
        if (deviceName == null || deviceName.isEmpty()) return false;
        String lower = deviceName.toLowerCase();
        for (String k : BIKE_COMPUTERS) {
            if (lower.contains(k)) return true;
        }
        return false;
    }

    static boolean sameEvent(Activity a, Activity b) {
        if (!java.util.Objects.equals(a.sport, b.sport) || "Other".equals(a.sport)) return false;
        LocalDateTime sa = start(a), sb = start(b);
        if (sa == null || sb == null) return false;
        // Fractional seconds, matching Python's timedelta.total_seconds().
        double gap = Math.abs(Duration.between(sa, sb).toNanos()) / 1e9;
        if (gap > START_TOLERANCE_S) return false;
        long da = a.movingTime == null ? 0 : a.movingTime;
        long db = b.movingTime == null ? 0 : b.movingTime;
        if (da <= 0 || db <= 0) return gap <= 120;  // tight start match fallback
        double ratio = (double) Math.min(da, db) / Math.max(da, db);
        return ratio >= DURATION_RATIO_MIN;
    }

    /** Groups (size ≥ 2) of activities that represent the same event. */
    public static List<List<Activity>> clusterDuplicates(List<Activity> activities) {
        List<Activity> ordered = new ArrayList<>(activities);
        ordered.sort(Comparator.comparing(a -> a.startDate == null ? "" : a.startDate));
        List<List<Activity>> clusters = new ArrayList<>();
        for (Activity act : ordered) {
            boolean placed = false;
            for (List<Activity> cluster : clusters) {
                if (cluster.stream().anyMatch(m -> sameEvent(act, m))) {
                    cluster.add(act);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                List<Activity> c = new ArrayList<>();
                c.add(act);
                clusters.add(c);
            }
        }
        List<List<Activity>> dups = new ArrayList<>();
        for (List<Activity> c : clusters) if (c.size() > 1) dups.add(c);
        return dups;
    }

    private static int hasPower(Activity a) {
        boolean pm = a.hasPowerMeter != null && a.hasPowerMeter != 0;
        boolean wp = a.weightedPower != null && a.weightedPower != 0.0;
        return (pm || wp) ? 1 : 0;
    }

    private static int hasHr(Activity a) {
        return (a.avgHr != null && a.avgHr != 0.0) ? 1 : 0;
    }

    // Source-preference score (higher = better), sport-aware. Ties keep the
    // first max, matching Python's max() over the start_date-ordered cluster.
    private static Comparator<Activity> scoreComparator(String sport) {
        Comparator<Activity> length = Comparator
                .comparingInt((Activity a) -> a.movingTime == null ? 0 : a.movingTime)
                .thenComparingDouble(a -> a.distance == null ? 0.0 : a.distance);
        if ("Bike".equals(sport)) {
            return Comparator.comparingInt((Activity a) -> isBikeComputer(a.deviceName) ? 1 : 0)
                    .thenComparingInt(Dedup::hasPower)
                    .thenComparingInt(Dedup::hasHr)
                    .thenComparing(length);
        }
        return Comparator.comparingInt((Activity a) -> isAppleWatch(a.deviceName) ? 1 : 0)
                .thenComparingInt(Dedup::hasHr)
                .thenComparingInt(Dedup::hasPower)
                .thenComparing(length);
    }

    /** Result of a dedup pass: the same-event clusters and the duplicate count. */
    public record Result(List<List<Activity>> clusters, int duplicates) {}

    /** clear_duplicate_flags → cluster → mark: reset every activity, cluster the
     * same-event ones, and flag all-but-the-primary in each cluster. Mutates the
     * passed (managed) entities. Shared by POST /dedup and the sync. */
    public static Result markDuplicates(List<Activity> acts) {
        for (Activity a : acts) {
            a.isDuplicate = 0;
            a.primaryId = null;
        }
        List<List<Activity>> clusters = clusterDuplicates(acts);
        int duplicates = 0;
        for (List<Activity> cluster : clusters) {
            Activity primary = primaryOf(cluster);
            for (Activity a : cluster) {
                boolean isDup = !a.id.equals(primary.id);
                a.isDuplicate = isDup ? 1 : 0;
                a.primaryId = primary.id;
                if (isDup) duplicates++;
            }
        }
        return new Result(clusters, duplicates);
    }

    /** The activity to keep from a duplicate cluster (sport-aware). */
    public static Activity primaryOf(List<Activity> cluster) {
        if (cluster.isEmpty()) return null;
        String sport = cluster.get(0).sport;
        Comparator<Activity> cmp = scoreComparator(sport);
        Activity best = cluster.get(0);
        for (int i = 1; i < cluster.size(); i++) {
            // Strictly-greater keeps the FIRST max on ties (Python max semantics).
            if (cmp.compare(cluster.get(i), best) > 0) best = cluster.get(i);
        }
        return best;
    }
}
