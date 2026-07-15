package io.gamov.irontrainer.metrics;

import io.gamov.irontrainer.auth.CurrentAthlete;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Performance Management Chart — port of the FastAPI /api/metrics/pmc.
 * Reads the athlete's stored daily metrics (CTL/ATL/TSB), windows them to the
 * last `days` (default 180, 0 = all), returns {days, window_days, total_days}.
 * No math: the rows are precomputed by the sync pipeline. */
@Path("/api/metrics/pmc")
public class PmcResource {

    @Inject
    CurrentAthlete current;

    @GET
    public Map<String, Object> pmc(@QueryParam("days") @DefaultValue("180") int days) {
        if (days < 0 || days > 3660) {  // matches FastAPI Query(ge=0, le=3660)
            throw new WebApplicationException("days must be 0..3660", 422);
        }
        int athleteId = current.require();
        List<MetricDaily> all = MetricDaily
                .list("athleteId = ?1 order by date", athleteId);
        int total = all.size();
        String cutoff = windowCutoff(days);  // null = unbounded
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MetricDaily m : all) {
            if (cutoff == null || m.date.compareTo(cutoff) >= 0) {
                rows.add(row(m));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", rows);
        out.put("window_days", days);
        out.put("total_days", total);
        return out;
    }

    /** Inclusive-of-today lower bound: days=180 spans today-179..today.
     * days<=0 → null (full history). Mirrors _window_cutoff in FastAPI. */
    static String windowCutoff(int days) {
        if (days <= 0) return null;
        return LocalDate.now().minusDays(days - 1L).toString();
    }

    /** Same key set as SQLModel model_dump(): athlete_id, date, tss/ctl/atl/tsb. */
    private static Map<String, Object> row(MetricDaily m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("athlete_id", m.athleteId);
        r.put("date", m.date);
        r.put("tss", m.tss);
        r.put("ctl", m.ctl);
        r.put("atl", m.atl);
        r.put("tsb", m.tsb);
        return r;
    }
}
