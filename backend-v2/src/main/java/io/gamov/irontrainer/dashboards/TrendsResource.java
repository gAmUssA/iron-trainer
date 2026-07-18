package io.gamov.irontrainer.dashboards;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.metrics.MetricDaily;
import io.gamov.irontrainer.races.Races;
import io.gamov.irontrainer.util.Params;
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

/** GET /api/metrics/trends — per-sport progression points (windowed by `days`)
 * plus the full insights bundle (rolling trendlines, verdicts, intensity mix,
 * PRs, CTL trajectory, freshness). Insights derive from the FULL record; `days`
 * only windows the returned chart points. Parity with analytics_router.trends. */
@Path("/api/metrics/trends")
public class TrendsResource {

    private static final int DEFAULT_WINDOW_DAYS = 180;

    @Inject
    CurrentAthlete current;

    @Inject
    Races races;

    @GET
    public Map<String, Object> trends(@QueryParam("days") @DefaultValue("180") String daysRaw) {
        int days = Params.intParam(daysRaw);
        if (days < 0 || days > 3660) {            // FastAPI Query(ge=0, le=3660)
            throw new WebApplicationException(422);
        }
        int aid = current.require();
        LocalDate today = LocalDate.now();

        List<Activity> acts = Activity.list(
                "athleteId = ?1 and (isDuplicate = 0 or isDuplicate is null) order by startDate", aid);
        Map<String, List<Map<String, Object>>> sportPoints = Dashboards.sportTrends(acts);

        Athlete a = Athlete.findById(aid);
        LocalDate raceDate = Insights.day((String) races.effectiveRace(a).get("date"));

        List<MetricDaily> metrics = MetricDaily.list("athleteId = ?1 order by date", aid);
        Map<String, Object> insights = Insights.build(acts, sportPoints, metrics, raceDate, today);

        // Window ONLY the returned chart points (days<=0 = unbounded).
        String cutoff = days <= 0 ? null : today.minusDays(days - 1L).toString();
        Map<String, Object> body = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : sportPoints.entrySet()) {
            List<Map<String, Object>> pts = e.getValue();
            if (cutoff != null) {
                List<Map<String, Object>> filtered = new ArrayList<>();
                for (Map<String, Object> p : pts) {
                    if (((String) p.get("date")).compareTo(cutoff) >= 0) {
                        filtered.add(p);
                    }
                }
                pts = filtered;
            }
            body.put(e.getKey(), pts);
        }
        body.put("insights", insights);
        body.put("window_days", days);
        return body;
    }
}
