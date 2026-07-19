package io.gamov.irontrainer.plan;

import io.gamov.irontrainer.util.Py;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure helpers for the weekly check-in — sanitize_feel + _feel_vs_data_line
 * (byte-parity strings). Separated from the resource for unit testing. */
public final class Checkins {

    private Checkins() {}

    /** Subjective check-in keys (all 1-5, higher is better). */
    static final List<String> FEEL_KEYS = List.of("energy", "sleep", "body", "stress");

    /** sanitize_feel: clamp inputs to the contract — known keys, ints 1-5, plus
     * an optional ≤280-char note; null when nothing valid remains. */
    public static Map<String, Object> sanitizeFeel(Map<String, Object> inputs) {
        if (inputs == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : FEEL_KEYS) {
            Object v = inputs.get(k);
            if (v instanceof Number n) {
                out.put(k, Math.max(1, Math.min(5, (int) n.doubleValue())));   // int(v) truncates
            }
        }
        Object note = inputs.get("note");
        if (note instanceof String s && !s.strip().isEmpty()) {
            String t = s.strip();
            out.put("note", t.length() > 280 ? t.substring(0, 280) : t);
        }
        return out.isEmpty() ? null : out;
    }

    /** _feel_vs_data_line: the feel-vs-data reconciliation sentence, or null. */
    public static String feelVsDataLine(Map<String, Object> feel, Map<String, Object> ready) {
        List<Integer> scores = new ArrayList<>();
        if (feel != null) {
            for (String k : FEEL_KEYS) {
                Object v = feel.get(k);
                if (v instanceof Number n) {
                    scores.add(n.intValue());
                }
            }
        }
        if (scores.isEmpty() || !"ok".equals(ready.get("status"))) {
            return null;
        }
        double avg = scores.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        String level = (String) ready.get("level");
        if (avg <= 2.5 && "green".equals(level)) {
            return "Feel vs data: you rate the week " + Py.f1(avg) + "/5 but your training load is "
                    + "steady — the fatigue is probably coming from sleep or life stress, not the plan. "
                    + "Worth a look.";
        }
        if (avg >= 3.5 && ("amber".equals(level) || "red".equals(level))) {
            return "Feel vs data: you feel good (" + Py.f1(avg) + "/5) but the numbers disagree — "
                    + firstReason(ready) + " Don't let a good day bait an overreach.";
        }
        return "Feel vs data: aligned (" + Py.f1(avg) + "/5 vs a " + level + " load picture).";
    }

    @SuppressWarnings("unchecked")
    static String firstReason(Map<String, Object> ready) {
        Object reasons = ready.get("reasons");
        if (reasons instanceof List<?> l && !l.isEmpty()) {
            return String.valueOf(l.get(0));
        }
        return "";
    }
}
