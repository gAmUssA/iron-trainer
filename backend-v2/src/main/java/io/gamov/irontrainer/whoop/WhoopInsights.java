package io.gamov.irontrainer.whoop;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/** Deterministic WHOOP insights (Steve Tan method, computed — the LLM narrates
 * these numbers instead of doing arithmetic): journal behavior→recovery/HRV
 * deltas, bedtime consistency, and 28-day strain/recovery direction. Pure
 * functions over the athlete's rows; anchored on the newest data date (not
 * "today") so a week-old export still analyzes its own last 28 days. */
public final class WhoopInsights {

    // A behavior needs this many scored days on BOTH sides before its delta
    // means anything.
    static final int MIN_DAYS_PER_SIDE = 5;
    static final int MAX_BEHAVIORS = 10;

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US);

    private WhoopInsights() {
    }

    /** @param cycles any order; @param journal any order. */
    public static Map<String, Object> compute(List<WhoopCycle> cycles, List<WhoopJournalEntry> journal) {
        Map<String, WhoopCycle> byDate = new TreeMap<>();
        for (WhoopCycle c : cycles) {
            byDate.put(c.date, c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", byDate.size());
        if (byDate.isEmpty()) {
            return out;
        }
        LocalDate anchor = LocalDate.parse(((TreeMap<String, WhoopCycle>) byDate).lastKey());
        out.put("anchor_date", anchor.toString());
        out.put("baseline_all", baseline(byDate.values()));
        out.put("baseline_90d", baseline(since(byDate, anchor.minusDays(89))));
        out.put("behaviors", behaviors(byDate, journal));
        out.put("bedtime", bedtime(byDate, anchor));
        out.put("trend_28d", trend(byDate, anchor));
        return out;
    }

    private static Map<String, Object> baseline(Iterable<WhoopCycle> cycles) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recovery", round1(mean(cycles, c -> c.recoveryScore)));
        m.put("hrv_ms", round1(mean(cycles, c -> c.hrvRmssdMs)));
        m.put("rhr_bpm", round1(mean(cycles, c -> c.rhrBpm)));
        m.put("strain", round1(mean(cycles, c -> c.dayStrain)));
        m.put("sleep_h", round1(mean(cycles, c -> c.asleepH)));
        m.put("sleep_performance", round1(mean(cycles, c -> c.sleepPerformancePct)));
        return m;
    }

    /** Per journal question: avg same-day recovery/HRV on yes-days vs no-days.
     * Only questions with ≥{@link #MIN_DAYS_PER_SIDE} scored days on each side;
     * ranked by |Δ recovery|, capped at {@link #MAX_BEHAVIORS}. */
    static List<Map<String, Object>> behaviors(Map<String, WhoopCycle> byDate,
                                               List<WhoopJournalEntry> journal) {
        record Acc(List<Double> recYes, List<Double> recNo, List<Double> hrvYes, List<Double> hrvNo) {}
        Map<String, Acc> byQuestion = new LinkedHashMap<>();
        for (WhoopJournalEntry j : journal) {
            if (j.answeredYes == null) {
                continue;
            }
            WhoopCycle c = byDate.get(j.date);
            if (c == null || c.recoveryScore == null) {
                continue;
            }
            Acc a = byQuestion.computeIfAbsent(j.question,
                    k -> new Acc(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
            (j.answeredYes ? a.recYes() : a.recNo()).add(c.recoveryScore);
            if (c.hrvRmssdMs != null) {
                (j.answeredYes ? a.hrvYes() : a.hrvNo()).add(c.hrvRmssdMs);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        byQuestion.forEach((q, a) -> {
            if (a.recYes().size() < MIN_DAYS_PER_SIDE || a.recNo().size() < MIN_DAYS_PER_SIDE) {
                return;
            }
            double recYes = mean(a.recYes());
            double recNo = mean(a.recNo());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("question", q);
            m.put("yes_days", a.recYes().size());
            m.put("no_days", a.recNo().size());
            m.put("recovery_yes", round1(recYes));
            m.put("recovery_no", round1(recNo));
            m.put("recovery_delta", round1(recYes - recNo));
            if (a.hrvYes().size() >= MIN_DAYS_PER_SIDE && a.hrvNo().size() >= MIN_DAYS_PER_SIDE) {
                m.put("hrv_delta", round1(mean(a.hrvYes()) - mean(a.hrvNo())));
            } else {
                m.put("hrv_delta", null);
            }
            rows.add(m);
        });
        rows.sort(Comparator.comparingDouble(m -> -Math.abs((Double) m.get("recovery_delta"))));
        return rows.size() > MAX_BEHAVIORS ? rows.subList(0, MAX_BEHAVIORS) : rows;
    }

    /** Bedtime consistency: circular std-dev (minutes) of the cycle-start
     * time-of-day over the last 28 anchored days, vs all time. Times are UTC —
     * a constant home-timezone offset cancels out of a spread; travel weeks
     * inflate it (approximate by design). Circular stats so 23:30 vs 00:30 is
     * 60 min apart, not 23 h. */
    static Map<String, Object> bedtime(Map<String, WhoopCycle> byDate, LocalDate anchor) {
        List<Double> all = new ArrayList<>();
        List<Double> last28 = new ArrayList<>();
        String cutoff = anchor.minusDays(27).toString();
        for (WhoopCycle c : byDate.values()) {
            Double minutes = minutesOfDay(c.cycleStart);
            if (minutes == null) {
                continue;
            }
            all.add(minutes);
            if (c.date.compareTo(cutoff) >= 0) {
                last28.add(minutes);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stddev_min_28d", round1(circularStdMinutes(last28)));
        m.put("stddev_min_all", round1(circularStdMinutes(all)));
        m.put("nights_28d", last28.size());
        return m;
    }

    /** 28-day means vs the previous 28 days — is load outpacing recovery? */
    static Map<String, Object> trend(Map<String, WhoopCycle> byDate, LocalDate anchor) {
        List<WhoopCycle> last = window(byDate, anchor.minusDays(27), anchor);
        List<WhoopCycle> prev = window(byDate, anchor.minusDays(55), anchor.minusDays(28));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("strain_28d", round1(mean(last, c -> c.dayStrain)));
        m.put("strain_prev_28d", round1(mean(prev, c -> c.dayStrain)));
        m.put("recovery_28d", round1(mean(last, c -> c.recoveryScore)));
        m.put("recovery_prev_28d", round1(mean(prev, c -> c.recoveryScore)));
        m.put("hrv_28d", round1(mean(last, c -> c.hrvRmssdMs)));
        m.put("hrv_prev_28d", round1(mean(prev, c -> c.hrvRmssdMs)));
        return m;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static List<WhoopCycle> since(Map<String, WhoopCycle> byDate, LocalDate from) {
        return window(byDate, from, LocalDate.MAX);
    }

    private static List<WhoopCycle> window(Map<String, WhoopCycle> byDate, LocalDate from, LocalDate to) {
        List<WhoopCycle> out = new ArrayList<>();
        String lo = from.toString();
        String hi = to.equals(LocalDate.MAX) ? "9999" : to.toString();
        for (Map.Entry<String, WhoopCycle> e : byDate.entrySet()) {
            if (e.getKey().compareTo(lo) >= 0 && e.getKey().compareTo(hi) <= 0) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    static Double minutesOfDay(String ts) {
        if (ts == null) {
            return null;
        }
        try {
            LocalDateTime t = LocalDateTime.parse(ts.strip(), TS);
            return (double) (t.getHour() * 60 + t.getMinute());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Circular std-dev in minutes over times-of-day (period 1440 min). */
    static Double circularStdMinutes(List<Double> minutes) {
        if (minutes.size() < 2) {
            return null;
        }
        double sumSin = 0;
        double sumCos = 0;
        for (double m : minutes) {
            double a = 2 * Math.PI * m / 1440.0;
            sumSin += Math.sin(a);
            sumCos += Math.cos(a);
        }
        double r = Math.hypot(sumSin, sumCos) / minutes.size();
        if (r <= 0) {
            return null;
        }
        if (r >= 1) {
            return 0.0;
        }
        return Math.sqrt(-2 * Math.log(r)) * 1440.0 / (2 * Math.PI);
    }

    private static double mean(List<Double> xs) {
        double s = 0;
        for (double x : xs) {
            s += x;
        }
        return s / xs.size();
    }

    private static Double mean(Iterable<WhoopCycle> cycles, Function<WhoopCycle, Double> f) {
        double s = 0;
        int n = 0;
        for (WhoopCycle c : cycles) {
            Double v = f.apply(c);
            if (v != null) {
                s += v;
                n++;
            }
        }
        return n == 0 ? null : s / n;
    }

    static Double round1(Double v) {
        return v == null ? null : Math.round(v * 10.0) / 10.0;
    }
}
