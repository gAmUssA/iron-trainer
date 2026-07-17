package io.gamov.irontrainer.dashboards;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.util.Params;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GET /api/metrics/weekly — weekly training volume by sport (parity with the
 * FastAPI analytics_router.weekly). Excludes duplicates (list_activities default). */
@Path("/api/metrics/weekly")
public class WeeklyResource {

    @Inject
    CurrentAthlete current;

    @GET
    public Map<String, Object> weekly(@QueryParam("weeks") @DefaultValue("16") String weeksRaw) {
        int weeks = Params.intParam(weeksRaw);
        int aid = current.require();
        List<Activity> acts = Activity.list(
                "athleteId = ?1 and (isDuplicate = 0 or isDuplicate is null) order by startDate", aid);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("weeks", Dashboards.weeklyVolume(acts, weeks));
        return body;
    }
}
