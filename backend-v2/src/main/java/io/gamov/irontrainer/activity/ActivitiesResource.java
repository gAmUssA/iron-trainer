package io.gamov.irontrainer.activity;

import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.util.Params;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GET /api/activities — the athlete's activity feed, most-recent first, capped
 * at `limit`. Parity with the FastAPI analytics_router.activities: `count` is the
 * TOTAL (unfiltered) activity count; `duplicates` counts dupes within the
 * returned page. raw_json is fetched with a projection for the page only (the
 * entity does not map it — see Activity). */
@Path("/api/activities")
public class ActivitiesResource {

    @Inject
    CurrentAthlete current;

    @Inject
    EntityManager em;

    @GET
    public Map<String, Object> activities(
            @QueryParam("limit") @DefaultValue("500") String limitRaw,
            @QueryParam("include_duplicates") @DefaultValue("true") String includeDuplicatesRaw) {
        int aid = current.require();
        int limit = Params.intParam(limitRaw);
        boolean includeDuplicates = Params.bool(includeDuplicatesRaw);

        List<Activity> acts = includeDuplicates
                ? Activity.list("athleteId = ?1 order by startDate", aid)
                : Activity.list(
                    "athleteId = ?1 and (isDuplicate = 0 or isDuplicate is null) order by startDate", aid);

        // list(reversed(acts))[:limit] — Python slice, negative-limit aware.
        Collections.reverse(acts);
        List<Activity> page = acts.subList(0, Params.sliceStop(limit, acts.size()));

        Map<Long, String> rawById = rawJsonFor(page);
        List<Map<String, Object>> out = new ArrayList<>(page.size());
        int dupCount = 0;
        for (Activity a : page) {
            Map<String, Object> dict = a.toDict();
            dict.put("raw_json", rawById.get(a.id));
            out.add(dict);
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

    /** raw_json for just the page's ids, in one query — avoids mapping the blob
     * on the shared entity (which would eager-load it on every bulk read). */
    private Map<Long, String> rawJsonFor(List<Activity> page) {
        if (page.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = new ArrayList<>(page.size());
        for (Activity a : page) {
            ids.add(a.id);
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, raw_json FROM activities WHERE id IN (:ids)")
                .setParameter("ids", ids)
                .getResultList();
        Map<Long, String> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put(((Number) row[0]).longValue(), (String) row[1]);
        }
        return map;
    }
}
