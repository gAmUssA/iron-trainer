package io.gamov.irontrainer.strava;

import io.gamov.irontrainer.metrics.Metrics;
import io.gamov.irontrainer.metrics.Metrics.Thresholds;
import io.gamov.irontrainer.metrics.Metrics.TssResult;
import io.gamov.irontrainer.util.PyJson;
import java.util.LinkedHashMap;
import java.util.Map;

/** Map a raw Strava activity (API or archive shape) to our activity row — port
 * of repo._map_activity. Pure: normalizes sport, computes TSS from thresholds,
 * extracts fields. The Strava-sync/import verticals feed the result to upsert. */
public final class StravaMapping {

    private StravaMapping() {}

    public static Map<String, Object> mapActivity(Map<String, Object> raw, Thresholds th, int aid) {
        Object typeKey = raw.get("sport_type") != null ? raw.get("sport_type") : raw.get("type");
        String sport = Metrics.normalizeSport(typeKey == null ? null : typeKey.toString());
        Double moving = asDouble(raw.get("moving_time"));
        Double distance = asDouble(raw.get("distance"));
        Double weighted = asDouble(raw.get("weighted_average_watts"));
        Double avgPower = asDouble(raw.get("average_watts"));
        Double avgHr = asDouble(raw.get("average_heartrate"));
        Double maxHr = asDouble(raw.get("max_heartrate"));
        int hasPm = truthy(raw.get("device_watts")) ? 1 : 0;
        TssResult r = Metrics.computeTss(sport, moving, distance, weighted, avgPower, avgHr, th);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", raw.get("id"));
        out.put("athlete_id", aid);
        out.put("sport", sport);
        out.put("start_date", raw.get("start_date_local") != null
                ? raw.get("start_date_local") : raw.get("start_date"));
        out.put("name", raw.get("name"));
        out.put("moving_time", raw.get("moving_time"));
        out.put("elapsed_time", raw.get("elapsed_time"));
        out.put("distance", distance);
        out.put("avg_power", avgPower);
        out.put("weighted_power", weighted);
        out.put("avg_hr", avgHr);
        out.put("max_hr", maxHr);
        out.put("avg_speed", asDouble(raw.get("average_speed")));
        out.put("elevation_gain", asDouble(raw.get("total_elevation_gain")));
        out.put("has_power_meter", hasPm);
        out.put("tss", r.tss());
        out.put("intensity_factor", r.intensityFactor());
        out.put("tss_method", r.method());
        out.put("created_at", PyJson.utcNowIso());
        return out;
    }

    private static Double asDouble(Object v) {
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0.0;
        if (v instanceof String) return !((String) v).isEmpty();  // Python: "" is falsy
        return true;
    }
}
