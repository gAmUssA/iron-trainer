package io.gamov.irontrainer.readiness;

import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.metrics.MetricDaily;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GET /api/metrics/readiness/today — the go hard / go easy / rest call from
 * the athlete's own load + recovery. Port of the FastAPI endpoint. */
@Path("/api/metrics/readiness/today")
public class ReadinessResource {

    @Inject
    CurrentAthlete current;

    @GET
    public Map<String, Object> today() {
        int aid = current.require();
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (MetricDaily m : MetricDaily.<MetricDaily>list("athleteId = ?1 order by date", aid)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("date", m.date);
            r.put("tss", m.tss);
            r.put("ctl", m.ctl);
            r.put("atl", m.atl);
            r.put("tsb", m.tsb);
            metrics.add(r);
        }
        List<Map<String, Object>> recovery = new ArrayList<>();
        // recent_recovery(35), newest-first.
        for (DailyRecovery dr : DailyRecovery.<DailyRecovery>find(
                "athleteId = ?1 order by date desc", aid).page(0, 35).list()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("date", dr.date);
            r.put("sleep_h", dr.sleepH);
            r.put("hrv_ms", dr.hrvMs);
            r.put("rhr_bpm", dr.rhrBpm);
            recovery.add(r);
        }
        return Readiness.compute(metrics, recovery, null);
    }
}
