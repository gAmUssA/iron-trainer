package io.gamov.irontrainer.athlete;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.Map;

import io.gamov.irontrainer.auth.CurrentAthlete;

/** GET /api/athlete — the athlete profile (thresholds + nutrition), plus a
 * connected flag. Port of athlete_router.get_profile. The PUT (edit thresholds)
 * is a separate slice — it recomputes TSS and refreshes future plan targets. */
@Path("/api/athlete")
public class ProfileResource {

    @Inject
    CurrentAthlete current;

    // repo._PUBLIC — the columns exposed to the client, in this order.
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Map<String, Object> getProfile() {
        Athlete a = Athlete.findById(current.require());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("strava_athlete_id", a == null ? null : a.stravaAthleteId);
        p.put("name", a == null ? null : a.name);
        p.put("ftp", a == null ? null : a.ftp);
        p.put("threshold_hr", a == null ? null : a.thresholdHr);
        p.put("max_hr", a == null ? null : a.maxHr);
        p.put("threshold_pace_run", a == null ? null : a.thresholdPaceRun);
        p.put("css_swim", a == null ? null : a.cssSwim);
        p.put("weekly_hours_target", a == null ? null : a.weeklyHoursTarget);
        p.put("body_weight_kg", a == null ? null : a.bodyWeightKg);
        p.put("gel_carb_g", a == null ? null : a.gelCarbG);
        p.put("sweat_rate_l_h", a == null ? null : a.sweatRateLH);
        p.put("gi_tolerance", a == null ? null : a.giTolerance);
        p.put("updated_at", a == null ? null : a.updatedAt);
        Map<String, Object> out = new LinkedHashMap<>();
        // bool(strava_refresh_token) — a non-empty refresh token means connected.
        out.put("connected", a != null && a.stravaRefreshToken != null && !a.stravaRefreshToken.isEmpty());
        out.put("profile", p);
        return out;
    }
}
