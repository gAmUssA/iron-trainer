package io.gamov.irontrainer.zones;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Port of app/zones.py — pure functions, byte-parity with the Python
 * response (band fractions, rounding, max-HR capping, the lo>=1 floor and
 * lo<=hi clamp for inconsistent thresholds). */
public final class HrZones {

    private HrZones() {}

    private record Band(String zone, String name, double lo, double hi) {}

    private static final List<Band> LTHR_BANDS = List.of(
            new Band("Z1", "Recovery", 0.00, 0.81),
            new Band("Z2", "Endurance", 0.81, 0.89),
            new Band("Z3", "Tempo", 0.90, 0.93),
            new Band("Z4", "Threshold", 0.94, 0.99),
            new Band("Z5", "VO2max", 1.00, 1.06));

    private static final List<Band> MAXHR_BANDS = List.of(
            new Band("Z1", "Recovery", 0.50, 0.60),
            new Band("Z2", "Endurance", 0.60, 0.70),
            new Band("Z3", "Tempo", 0.70, 0.80),
            new Band("Z4", "Threshold", 0.80, 0.90),
            new Band("Z5", "VO2max", 0.90, 1.00));

    public static Map<String, Object> hrZones(Integer thresholdHr, Integer maxHr) {
        double base;
        List<Band> bands;
        String basis;
        if (thresholdHr != null && thresholdHr > 0) {
            base = thresholdHr;
            bands = LTHR_BANDS;
            basis = "lthr";
        } else if (maxHr != null && maxHr > 0) {
            base = maxHr;
            bands = MAXHR_BANDS;
            basis = "max_hr";
        } else {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("basis", null);
            empty.put("zones", List.of());
            return empty;
        }
        List<Map<String, Object>> zones = new ArrayList<>();
        for (Band b : bands) {
            long hi = Math.round(base * b.hi());
            if (maxHr != null && maxHr > 0) {
                hi = Math.min(hi, maxHr);
            }
            long lo = Math.max(1, Math.round(base * b.lo()));
            lo = Math.min(lo, hi);
            Map<String, Object> z = new LinkedHashMap<>();
            z.put("zone", b.zone());
            z.put("name", b.name());
            z.put("low", lo);
            z.put("high", hi);
            zones.add(z);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("basis", basis);
        out.put("zones", zones);
        return out;
    }
}
