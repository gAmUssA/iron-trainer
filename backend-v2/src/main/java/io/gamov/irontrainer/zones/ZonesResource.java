package io.gamov.irontrainer.zones;

import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.auth.CurrentAthlete;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.Map;

/** First domain-math vertical: same contract as FastAPI's /api/athlete/zones. */
@Path("/api/athlete/zones")
public class ZonesResource {

    @Inject
    CurrentAthlete current;

    @GET
    public Map<String, Object> zones() {
        Athlete a = Athlete.findById(current.require());
        Integer thr = a == null ? null : a.thresholdHr;
        Integer max = a == null ? null : a.maxHr;
        Map<String, Object> out = new java.util.LinkedHashMap<>(HrZones.hrZones(thr, max));
        // Contract parity with FastAPI: thresholds echoed alongside the table.
        out.put("threshold_hr", thr);
        out.put("max_hr", max);
        return out;
    }
}
