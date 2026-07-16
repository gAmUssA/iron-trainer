package io.gamov.irontrainer.readiness;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Daily readiness call — faithful port of app/readiness.py. Pure function.
 * Reason strings and numeric fields must match FastAPI char-for-char (see Py).
 *
 * A "metric row" is a Map with keys date (String), tss/ctl/atl/tsb (Double|null).
 * A "recovery row" is a Map with date/sleep_h/hrv_ms/rhr_bpm. */
public final class Readiness {

    private Readiness() {}

    static final double ACWR_HIGH = 1.5;
    static final double ACWR_ELEVATED = 1.3;
    static final double ACWR_LOW = 0.8;
    static final double TSB_FATIGUED = -25.0;
    static final double TSB_FRESH = 10.0;
    static final double HARD_DAY_FACTOR = 1.5;
    static final double HARD_DAY_MIN_TSS = 50.0;
    static final int MIN_HISTORY_DAYS = 14;
    static final double MIN_CHRONIC_WEEKLY_TSS = 30.0;

    static final int RECOVERY_FRESH_DAYS = 2;
    static final double SHORT_SLEEP_H = 6.0;
    static final double HRV_SUPPRESSED_RATIO = 0.80;
    static final double RHR_ELEVATED_BPM = 5.0;
    static final int MIN_BASELINE_SAMPLES = 5;

    /** Clock for resolving "today" when the caller passes null. UTC by default so
     * the readiness day matches FastAPI (also pinned to UTC via _utcnow) no matter
     * the container timezone — otherwise the two backends could disagree on the
     * call near local midnight. Package-visible so tests can freeze it. */
    static Clock clock = Clock.systemUTC();

    public static Map<String, Object> compute(List<Map<String, Object>> metricRows,
                                              List<Map<String, Object>> recovery,
                                              LocalDate today) {
        if (today == null) today = LocalDate.now(clock);

        Map<LocalDate, Map<String, Object>> byDay = new LinkedHashMap<>();
        for (Map<String, Object> r : metricRows) {
            LocalDate d = parseDate(r.get("date"));
            if (d != null) byDay.put(d, r);
        }

        final LocalDate t = today;
        List<LocalDate> present = byDay.keySet().stream().filter(d -> d.isBefore(t)).toList();
        if (present.isEmpty()) {
            return insufficient("Not enough training history yet for a readiness call (need ~2 weeks of data).");
        }
        // Earliest (history-length guard) and latest (freshness) day in one pass —
        // no assumption that `present` is sorted.
        LocalDate earliestDay = present.get(0), latestDay = present.get(0);
        for (LocalDate d : present) {
            if (d.isBefore(earliestDay)) earliestDay = d;
            if (d.isAfter(latestDay)) latestDay = d;
        }
        if (daysBetween(earliestDay, today) < MIN_HISTORY_DAYS) {
            return insufficient("Not enough training history yet for a readiness call (need ~2 weeks of data).");
        }

        double acute = 0, total28 = 0;
        for (int i = 1; i <= 7; i++) acute += tss(byDay, today.minusDays(i));
        for (int i = 1; i <= 28; i++) total28 += tss(byDay, today.minusDays(i));
        double chronicWeekly = total28 / 4.0;

        Map<String, Object> latest = byDay.get(latestDay);
        Double tsb = asDouble(latest.get("tsb"));
        Double ctl = asDouble(latest.get("ctl"));
        boolean tsbCurrent = daysBetween(latestDay, today) <= 3;

        if (chronicWeekly < MIN_CHRONIC_WEEKLY_TSS) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "insufficient_data");
            out.put("call", null);
            out.put("level", null);
            out.put("acute_7d", Py.round(acute, 1));
            out.put("chronic_weekly", Py.round(chronicWeekly, 1));
            out.put("tsb", tsb);
            out.put("ctl", ctl);
            out.put("reasons", List.of("Recent training volume is too low to compute a meaningful load ratio."));
            return out;
        }

        double acwr = acute / chronicWeekly;

        double chronicDaily = chronicWeekly / 7.0;
        double hardCut = Math.max(HARD_DAY_MIN_TSS, HARD_DAY_FACTOR * chronicDaily);
        int streak = 0;
        LocalDate d = today.minusDays(1);
        while (tss(byDay, d) >= hardCut) {
            streak++;
            d = d.minusDays(1);
        }

        List<String> reasons = new ArrayList<>();
        String call, level;
        if (acwr > ACWR_HIGH) {
            call = "rest"; level = "red";
            reasons.add("Load spike: your 7-day load (" + Py.f0(acute) + " TSS) is " + Py.f2(acwr)
                    + "x your 4-week norm (" + Py.f0(chronicWeekly) + "/wk) — injury-risk territory. Absorb it.");
        } else if (acwr > ACWR_ELEVATED) {
            call = "easy"; level = "amber";
            reasons.add("Load is ramping fast: 7-day load " + Py.f0(acute) + " TSS vs a 4-week norm of "
                    + Py.f0(chronicWeekly) + " (ratio " + Py.f2(acwr) + "). Keep today easy and let it settle.");
        } else if (tsbCurrent && tsb != null && tsb < TSB_FATIGUED) {
            call = "easy"; level = "amber";
            reasons.add("Deep fatigue: form (TSB) is " + Py.f0signed(tsb) + ". The load ratio is fine ("
                    + Py.f2(acwr) + "), but you're digging a hole — go easy until form recovers.");
        } else if (streak >= 2) {
            call = "easy"; level = "amber";
            reasons.add(streak + " hard days in a row (each ≥" + Py.f0(hardCut)
                    + " TSS). The adaptation happens in the easy day — take it.");
        } else {
            call = "hard"; level = "green";
            if (acwr < ACWR_LOW) {
                String tsbNote = (tsbCurrent && tsb != null && tsb > TSB_FRESH)
                        ? ", TSB " + Py.f0signed(tsb) : "";
                reasons.add("You're fresh — 7-day load " + Py.f0(acute) + " TSS is well under your "
                        + Py.f0(chronicWeekly) + "/wk norm (ratio " + Py.f2(acwr) + tsbNote
                        + "). Prime day for a key session.");
            } else {
                reasons.add("Load is steady (7-day " + Py.f0(acute) + " TSS vs " + Py.f0(chronicWeekly)
                        + "/wk norm, ratio " + Py.f2(acwr) + "). Green light for quality if the plan calls for it.");
            }
        }

        List<String> recFlags = recoveryFlags(recovery == null ? List.of() : recovery, today);
        if (!recFlags.isEmpty()) {
            if (call.equals("hard")) {
                call = "easy"; level = "amber";
                List<String> merged = new ArrayList<>(recFlags);
                merged.addAll(reasons);
                reasons = merged;
            } else {
                reasons.addAll(recFlags);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("call", call);
        out.put("level", level);
        out.put("acwr", Py.round(acwr, 2));
        out.put("acute_7d", Py.round(acute, 1));
        out.put("chronic_weekly", Py.round(chronicWeekly, 1));
        out.put("tsb", tsb);
        out.put("ctl", ctl);
        out.put("hard_day_streak", streak);
        out.put("reasons", reasons);
        return out;
    }

    public static String storyLine(Map<String, Object> r) {
        if (!"ok".equals(r.get("status"))) return null;
        String call = String.valueOf(r.getOrDefault("call", ""));
        String label = switch (call) {
            case "hard" -> "GO HARD";
            case "easy" -> "GO EASY";
            case "rest" -> "REST";
            default -> call.toUpperCase();
        };
        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) r.get("reasons");
        String reason = (reasons != null && !reasons.isEmpty()) ? reasons.get(0) : "";
        return "Today's call: " + label + " — " + reason;
    }

    private static List<String> recoveryFlags(List<Map<String, Object>> recovery, LocalDate today) {
        List<Object[]> byDate = new ArrayList<>();
        for (Map<String, Object> r : recovery) {
            LocalDate d = parseDate(r.get("date"));
            if (d != null && !d.isAfter(today)) byDate.add(new Object[]{d, r});
        }
        byDate.sort((a, b) -> ((LocalDate) b[0]).compareTo((LocalDate) a[0]));  // newest first
        if (byDate.isEmpty() || daysBetween((LocalDate) byDate.get(0)[0], today) > RECOVERY_FRESH_DAYS) {
            return List.of();
        }
        LocalDate latestDay = (LocalDate) byDate.get(0)[0];
        @SuppressWarnings("unchecked")
        Map<String, Object> latest = (Map<String, Object>) byDate.get(0)[1];
        List<String> flags = new ArrayList<>();

        Double sleep = asDouble(latest.get("sleep_h"));
        if (sleep != null && sleep > 0 && sleep < SHORT_SLEEP_H) {
            flags.add("Short sleep: " + Py.f1(sleep) + "h last night — recovery took the hit before training did.");
        }

        Double hrv = asDouble(latest.get("hrv_ms"));
        Double hrvBase = baseline(byDate, latestDay, "hrv_ms");
        if (hrv != null && hrvBase != null && hrvBase > 0 && hrv < HRV_SUPPRESSED_RATIO * hrvBase) {
            flags.add("HRV suppressed: " + Py.f0(hrv) + " ms vs your ~" + Py.f0(hrvBase) + " ms baseline ("
                    + Py.pct0(hrv / hrvBase) + ") — the nervous system wants an easy day.");
        }

        Double rhr = asDouble(latest.get("rhr_bpm"));
        Double rhrBase = baseline(byDate, latestDay, "rhr_bpm");
        if (rhr != null && rhrBase != null && rhrBase > 0 && rhr > rhrBase + RHR_ELEVATED_BPM) {
            flags.add("Resting HR elevated: " + Py.f0(rhr) + " bpm vs your ~" + Py.f0(rhrBase)
                    + " bpm baseline — watch for illness or accumulated fatigue.");
        }
        return flags;
    }

    private static Double baseline(List<Object[]> byDate, LocalDate latestDay, String field) {
        List<Double> vals = new ArrayList<>();
        for (Object[] row : byDate) {
            LocalDate d = (LocalDate) row[0];
            if (d.isBefore(latestDay)) {
                @SuppressWarnings("unchecked")
                Double v = asDouble(((Map<String, Object>) row[1]).get(field));
                if (v != null) vals.add(v);
            }
        }
        if (vals.size() < MIN_BASELINE_SAMPLES) return null;
        double sum = 0;
        for (double v : vals) sum += v;
        return sum / vals.size();
    }

    private static Map<String, Object> insufficient(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "insufficient_data");
        out.put("call", null);
        out.put("level", null);
        out.put("reasons", List.of(reason));
        return out;
    }

    private static double tss(Map<LocalDate, Map<String, Object>> byDay, LocalDate d) {
        Map<String, Object> r = byDay.get(d);
        Double v = r == null ? null : asDouble(r.get("tss"));
        return v == null ? 0.0 : v;
    }

    private static Double asDouble(Object o) {
        return (o instanceof Number n) ? n.doubleValue() : null;
    }

    private static LocalDate parseDate(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        if (s.length() < 10) return null;
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private static int daysBetween(LocalDate a, LocalDate b) {
        return (int) java.time.temporal.ChronoUnit.DAYS.between(a, b);
    }
}
