package io.gamov.irontrainer.dashboards;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.metrics.Metrics.Thresholds;
import io.gamov.irontrainer.metrics.MetricDaily;
import io.gamov.irontrainer.metrics.MetricsWrite;
import io.gamov.irontrainer.races.Races;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GET /api/metrics/readiness — projected race splits at current fitness vs the
 * cut-offs. Parity with analytics_router.race_readiness. (Distinct from
 * /api/metrics/readiness/today, the daily go-hard/easy call.) */
@Path("/api/metrics/readiness")
public class RaceReadinessResource {

    @Inject
    CurrentAthlete current;

    @Inject
    Races races;

    @GET
    public Map<String, Object> raceReadiness() {
        int aid = current.require();

        List<MetricDaily> metrics = MetricDaily.list("athleteId = ?1 order by date", aid);
        Double currentCtl = metrics.isEmpty() ? null : metrics.get(metrics.size() - 1).ctl;

        Athlete a = Athlete.findById(aid);
        Map<String, Object> race = races.effectiveRace(a);
        Map<String, Integer> cutoffs = new LinkedHashMap<>();
        cutoffs.put("swim", (Integer) race.get("cutoff_swim_s"));
        cutoffs.put("bike", (Integer) race.get("cutoff_bike_s"));
        cutoffs.put("finish", (Integer) race.get("cutoff_finish_s"));
        String distance = (String) race.get("distance");

        List<Activity> acts = Activity.list(
                "athleteId = ?1 and (isDuplicate = 0 or isDuplicate is null) order by startDate", aid);
        Thresholds th = MetricsWrite.thresholds(aid);

        return RaceReadiness.raceReadiness(acts, th, currentCtl, cutoffs, distance);
    }
}
