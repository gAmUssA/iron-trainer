package io.gamov.irontrainer.athlete;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.metrics.Metrics;
import io.gamov.irontrainer.metrics.Metrics.Thresholds;
import io.gamov.irontrainer.metrics.Metrics.TssResult;
import io.gamov.irontrainer.metrics.MetricsWrite;
import io.gamov.irontrainer.plan.PlanTargets;
import io.gamov.irontrainer.util.PyJson;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jboss.logging.Logger;

/** /api/athlete profile: read (get_profile) + edit thresholds (update_profile).
 * Rooted at {@code /api} so it pools with the other /api/athlete/* resources
 * rather than shadowing them (a class @Path of /api/athlete is the longest prefix
 * match for /api/athlete/race and would 404 it). Port of
 * athlete_router.get_profile / update_profile. */
@Path("/api")
public class ProfileResource {

    private static final Logger LOG = Logger.getLogger(ProfileResource.class);

    // Threshold/nutrition fields that drive workout prescriptions — changing any
    // means future planned workouts should be re-derived (_TARGET_FIELDS).
    private static final Set<String> TARGET_FIELDS = Set.of(
            "ftp", "threshold_hr", "max_hr", "threshold_pace_run", "css_swim",
            "body_weight_kg", "gel_carb_g", "sweat_rate_l_h");

    @Inject
    CurrentAthlete current;

    @Inject
    PlanTargets planTargets;

    @GET
    @Path("/athlete")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Map<String, Object> getProfile() {
        return profileResponse(Athlete.findById(current.require()));
    }

    /** PUT /api/athlete/profile — edit thresholds. exclude_unset semantics: only
     * keys the client SENT are applied (a sent null clears the field; an unsent
     * field is untouched). A threshold change recomputes TSS for every activity,
     * rebuilds the PMC, and refreshes future plan targets. */
    @PUT
    @Path("/athlete/profile")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> updateProfile(Map<String, Object> body) {
        // Validate BEFORE the auth check: FastAPI's pydantic 422 precedes the 401
        // that save_profile → current_athlete_id would raise, so an invalid body is
        // 422 even for an unauthenticated caller.
        Map<String, Object> changes = validate(body == null ? Map.of() : body);
        int aid = current.require();

        Map<String, Object> before = QuarkusTransaction.requiringNew().call(() -> targetSnapshot(aid));
        QuarkusTransaction.requiringNew().run(() -> saveProfile(aid, changes));
        // Threshold changes alter TSS for every activity and thus the whole PMC.
        QuarkusTransaction.requiringNew().run(() -> recomputeTss(aid));
        QuarkusTransaction.requiringNew().run(() -> MetricsWrite.rebuildMetrics(aid));

        Map<String, Object> out = QuarkusTransaction.requiringNew().call(
                () -> profileResponse(Athlete.findById(aid)));
        // New thresholds → refresh FUTURE workout targets (never past/current week).
        // Best-effort: the save is already committed, so a refresh failure must not
        // turn a successful save into a 500.
        if (targetChanged(changes, before)) {
            try {
                out.put("plan_weeks_refreshed", planTargets.refreshFuture(aid, LocalDate.now()));
            } catch (RuntimeException e) {
                LOG.error("Future-target refresh failed after profile update.", e);
                out.put("plan_weeks_refreshed", 0);
            }
        }
        return out;
    }

    // ── response ──────────────────────────────────────────────────────────────

    /** {connected, profile{…_PUBLIC…}} — the get_profile body, in _PUBLIC order. */
    private static Map<String, Object> profileResponse(Athlete a) {
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

    // ── validation (ProfileUpdate: Field bounds → 422) ──────────────────────────

    /** exclude_unset: only present model keys → changes (null clears); unknown
     * keys ignored (pydantic default). Out-of-bounds / wrong-type → 422. */
    private static Map<String, Object> validate(Map<String, Object> body) {
        Map<String, Object> c = new LinkedHashMap<>();
        floatField(body, c, "ftp", 0, 1000);
        intField(body, c, "threshold_hr", 60, 250);
        intField(body, c, "max_hr", 60, 250);
        floatField(body, c, "threshold_pace_run", 90, 1200);
        floatField(body, c, "css_swim", 40, 600);
        floatField(body, c, "weekly_hours_target", 0, 60);
        floatField(body, c, "body_weight_kg", 25, 250);
        floatField(body, c, "gel_carb_g", 5, 120);
        floatField(body, c, "sweat_rate_l_h", 0, 5);
        if (body.containsKey("gi_tolerance")) {
            Object v = body.get("gi_tolerance");
            if (v == null) {
                c.put("gi_tolerance", null);
            } else if (v instanceof String s && Set.of("low", "medium", "high").contains(s)) {
                c.put("gi_tolerance", s);
            } else {
                throw new WebApplicationException(422);
            }
        }
        return c;
    }

    /** Field(gt, lt) as a float — strictly >gt AND <lt. */
    private static void floatField(Map<String, Object> body, Map<String, Object> c, String name,
                                   double gt, double lt) {
        if (!body.containsKey(name)) {
            return;
        }
        Object v = body.get(name);
        if (v == null) {
            c.put(name, null);
            return;
        }
        if (v instanceof Boolean || !(v instanceof Number n)) {
            throw new WebApplicationException(422);
        }
        double d = n.doubleValue();
        if (d <= gt || d >= lt) {
            throw new WebApplicationException(422);
        }
        c.put(name, d);
    }

    /** Field(gt, lt) as an int — a fractional value is rejected (pydantic int). */
    private static void intField(Map<String, Object> body, Map<String, Object> c, String name,
                                 int gt, int lt) {
        if (!body.containsKey(name)) {
            return;
        }
        Object v = body.get(name);
        if (v == null) {
            c.put(name, null);
            return;
        }
        if (v instanceof Boolean || !(v instanceof Number n)) {
            throw new WebApplicationException(422);
        }
        double d = n.doubleValue();
        if (d != Math.floor(d) || Double.isInfinite(d)) {
            throw new WebApplicationException(422);   // int field, non-integer value
        }
        if (d <= gt || d >= lt) {
            throw new WebApplicationException(422);
        }
        c.put(name, (int) d);
    }

    // ── persistence ─────────────────────────────────────────────────────────

    private static Map<String, Object> targetSnapshot(int aid) {
        Athlete a = Athlete.findById(aid);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ftp", a == null ? null : a.ftp);
        m.put("threshold_hr", a == null ? null : a.thresholdHr);
        m.put("max_hr", a == null ? null : a.maxHr);
        m.put("threshold_pace_run", a == null ? null : a.thresholdPaceRun);
        m.put("css_swim", a == null ? null : a.cssSwim);
        m.put("body_weight_kg", a == null ? null : a.bodyWeightKg);
        m.put("gel_carb_g", a == null ? null : a.gelCarbG);
        m.put("sweat_rate_l_h", a == null ? null : a.sweatRateLH);
        return m;
    }

    private static boolean targetChanged(Map<String, Object> changes, Map<String, Object> before) {
        for (String k : TARGET_FIELDS) {
            if (changes.containsKey(k) && !Objects.equals(changes.get(k), before.get(k))) {
                return true;
            }
        }
        return false;
    }

    /** save_profile: set present keys (a null clears), bump updated_at if changed. */
    private static void saveProfile(int aid, Map<String, Object> changes) {
        Athlete a = Athlete.findById(aid);
        if (a == null) {
            return;
        }
        boolean changed = false;
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            setField(a, e.getKey(), e.getValue());
            changed = true;
        }
        if (changed) {
            a.updatedAt = PyJson.utcNowIso();
        }
    }

    private static void setField(Athlete a, String k, Object v) {
        switch (k) {
            case "ftp" -> a.ftp = (Double) v;
            case "threshold_hr" -> a.thresholdHr = (Integer) v;
            case "max_hr" -> a.maxHr = (Integer) v;
            case "threshold_pace_run" -> a.thresholdPaceRun = (Double) v;
            case "css_swim" -> a.cssSwim = (Double) v;
            case "weekly_hours_target" -> a.weeklyHoursTarget = (Double) v;
            case "body_weight_kg" -> a.bodyWeightKg = (Double) v;
            case "gel_carb_g" -> a.gelCarbG = (Double) v;
            case "sweat_rate_l_h" -> a.sweatRateLH = (Double) v;
            case "gi_tolerance" -> a.giTolerance = (String) v;
            default -> { }
        }
    }

    /** recompute_tss: recost every activity's TSS from the current thresholds. */
    private static void recomputeTss(int aid) {
        Thresholds th = MetricsWrite.thresholds(aid);
        List<Activity> acts = Activity.list("athleteId = ?1", aid);
        for (Activity a : acts) {
            TssResult r = Metrics.computeTss(a.sport,
                    a.movingTime == null ? null : a.movingTime.doubleValue(),
                    a.distance, a.weightedPower, a.avgPower, a.avgHr, th);
            a.tss = r.tss();
            a.intensityFactor = r.intensityFactor();
            a.tssMethod = r.method();
        }
    }
}
