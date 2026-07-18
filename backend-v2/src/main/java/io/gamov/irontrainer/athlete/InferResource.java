package io.gamov.irontrainer.athlete;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.metrics.MetricsWrite;
import io.gamov.irontrainer.util.Params;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/** POST /api/athlete/infer — preview thresholds inferred from history, and
 * optionally (?apply=1) persist them + rebuild metrics. Parity with
 * athlete_router.infer. */
@Path("/api/athlete/infer")
public class InferResource {

    private static final Logger LOG = Logger.getLogger(InferResource.class);

    @Inject
    CurrentAthlete current;

    @POST
    @Transactional
    public Map<String, Object> infer(@QueryParam("apply") String applyParam) {
        int aid = current.require();
        boolean apply = applyParam != null && Params.bool(applyParam);   // FastAPI apply: bool = False

        List<Activity> acts = Activity.list(
                "athleteId = ?1 and (isDuplicate = 0 or isDuplicate is null) order by startDate", aid);
        Map<String, Object> inferred = Analysis.inferProfile(acts, LocalDate.now());   // date.today()

        if (apply) {
            // Inference must never CLEAR a manually-set value it couldn't compute.
            Analysis.saveInferred(Athlete.findById(aid), inferred);
            MetricsWrite.recomputeAndRebuild(aid);
            LOG.debugf("Applied inferred thresholds for athlete=%d", aid);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inferred", inferred);
        out.put("applied", apply);
        return out;
    }
}
