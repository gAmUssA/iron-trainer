package io.gamov.irontrainer.dashboards;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.util.Iso;
import io.gamov.irontrainer.util.Params;
import io.gamov.irontrainer.util.Py;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Dashboard aggregations — port of backend/app/dashboards.py. So far:
 * weekly_volume (per-ISO-week training volume by sport). */
public final class Dashboards {

    private Dashboards() {}

    /** weekly_volume: actual volume per ISO week (Monday start), by sport, with
     * per-sport and total hours/distance_km/tss rounded to 1dp. Mirrors the
     * FastAPI quirk that totals sum the ALREADY-ROUNDED per-sport values. */
    public static List<Map<String, Object>> weeklyVolume(List<Activity> activities, int weeks) {
        // week_start → sport → [hours, distance_km, tss] (raw accumulators).
        // TreeMap keeps weeks in ascending date order (matches `sorted(buckets)`).
        Map<LocalDate, Map<String, double[]>> buckets = new TreeMap<>();
        for (Activity a : activities) {
            LocalDate d = day(a.startDate);
            if (d == null) {
                continue;
            }
            LocalDate ws = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            String sport = a.sport != null ? a.sport : "Other";
            Map<String, double[]> bySport = buckets.computeIfAbsent(ws, k -> new LinkedHashMap<>());
            double[] s = bySport.computeIfAbsent(sport, k -> new double[3]);
            s[0] += (a.movingTime != null ? a.movingTime : 0) / 3600.0;
            s[1] += (a.distance != null ? a.distance : 0.0) / 1000.0;
            s[2] += (a.tss != null ? a.tss : 0.0);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<LocalDate, Map<String, double[]>> e : buckets.entrySet()) {
            Map<String, Object> bySport = new LinkedHashMap<>();
            double totalHours = 0.0;
            double totalTss = 0.0;
            for (Map.Entry<String, double[]> se : e.getValue().entrySet()) {
                double[] v = se.getValue();
                double hours = Py.round(v[0], 1);
                double km = Py.round(v[1], 1);
                double tss = Py.round(v[2], 1);
                Map<String, Object> sv = new LinkedHashMap<>();
                sv.put("hours", hours);
                sv.put("distance_km", km);
                sv.put("tss", tss);
                bySport.put(se.getKey(), sv);
                totalHours += hours;   // sum of the ROUNDED per-sport values
                totalTss += tss;
            }
            Map<String, Object> wk = new LinkedHashMap<>();
            wk.put("week_start", e.getKey().toString());
            wk.put("by_sport", bySport);
            wk.put("total_hours", Py.round(totalHours, 1));
            wk.put("total_tss", Py.round(totalTss, 1));
            out.add(wk);
        }
        // out[-weeks:] — Python slice start, negative-weeks aware.
        int start = Params.sliceStart(-weeks, out.size());
        return new ArrayList<>(out.subList(start, out.size()));
    }

    /** _day: the calendar date of a stored ISO start_date. Iso.parseDate already
     * mirrors datetime.fromisoformat(s.replace("Z","+00:00")).date(). */
    private static LocalDate day(String s) {
        return Iso.parseDate(s);
    }
}
