package io.gamov.irontrainer.activity;

import io.gamov.irontrainer.auth.CurrentAthlete;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GET /api/activities — the athlete's activity feed, most-recent first, capped
 * at `limit`. Parity with the FastAPI analytics_router.activities: `count` is the
 * TOTAL (unfiltered) activity count; `duplicates` counts dupes within the
 * returned page. */
@Path("/api/activities")
public class ActivitiesResource {

    @Inject
    CurrentAthlete current;

    @GET
    public Map<String, Object> activities(
            @QueryParam("limit") @DefaultValue("500") int limit,
            @QueryParam("include_duplicates") @DefaultValue("true") boolean includeDuplicates) {
        int aid = current.require();
        List<Activity> acts = includeDuplicates
                ? Activity.list("athleteId = ?1 order by startDate", aid)
                : Activity.list(
                    "athleteId = ?1 and (isDuplicate = 0 or isDuplicate is null) order by startDate", aid);

        // reversed(acts)[:limit] — most-recent first, capped.
        List<Map<String, Object>> out = new ArrayList<>();
        int dupCount = 0;
        for (int i = acts.size() - 1; i >= 0 && out.size() < limit; i--) {
            Activity a = acts.get(i);
            out.add(a.toDict());
            if (a.isDuplicate != null && a.isDuplicate != 0) {
                dupCount++;
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", (int) Activity.count("athleteId", aid));  // total, unfiltered
        body.put("duplicates", dupCount);
        body.put("activities", out);
        return body;
    }
}
