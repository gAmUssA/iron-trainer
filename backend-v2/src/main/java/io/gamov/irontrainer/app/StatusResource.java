package io.gamov.irontrainer.app;

import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.races.Races;
import io.gamov.irontrainer.strava.StravaOAuth;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** App-level endpoints: onboarding status, current auth state, logout. Port of
 * main.status + auth_router.me/logout. */
@Path("/api")
public class StatusResource {

    // Matches FastAPI app.__version__ (backend/app/__init__.py).
    static final String VERSION = "0.1.0";

    @Inject
    CurrentAthlete current;

    @Inject
    Races races;

    @Inject
    StravaOAuth strava;

    @ConfigProperty(name = "irontrainer.auth-required")
    boolean authRequired;

    @ConfigProperty(name = "irontrainer.cookie-secure")
    boolean cookieSecure;

    @ConfigProperty(name = "quarkus.langchain4j.anthropic.api-key")
    Optional<String> anthropicKey;

    /** GET /api/status — config & setup status for the frontend onboarding. */
    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Map<String, Object> status() {
        Integer aid = current.idOrNull();
        Athlete a = aid == null ? null : Athlete.findById(aid);
        // effective_race when authenticated; the config default otherwise
        // (effectiveRace(null) already returns the config default race).
        Map<String, Object> race = races.effectiveRace(a);
        Map<String, Object> raceOut = new LinkedHashMap<>();
        raceOut.put("name", race.get("name"));
        raceOut.put("date", race.get("date"));
        raceOut.put("distance", race.get("distance"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", VERSION);
        out.put("race", raceOut);
        out.put("strava_configured", strava.configured());
        out.put("anthropic_configured", anthropicConfigured());
        out.put("auth_required", authRequired);
        out.put("authenticated", aid != null);
        return out;
    }

    /** GET /api/me — current auth state (drives the frontend login gate). */
    @GET
    @Path("/me")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Map<String, Object> me() {
        Integer aid = current.idOrNull();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("authenticated", aid != null);
        out.put("auth_required", authRequired);
        if (aid != null) {
            Athlete a = Athlete.findById(aid);
            Map<String, Object> ath = new LinkedHashMap<>();
            ath.put("name", a == null ? null : a.name);
            ath.put("strava_athlete_id", a == null ? null : a.stravaAthleteId);
            out.put("athlete", ath);
        } else {
            out.put("athlete", null);
        }
        return out;
    }

    /** POST /api/auth/logout — clear the session. Starlette's SessionMiddleware
     * emits the delete Set-Cookie ONLY when the request carried a session (an
     * empty-session request — e.g. a bearer-only client — gets none), so mirror
     * that: emit the delete cookie only when a `session` cookie was present. */
    @POST
    @Path("/auth/logout")
    @Produces(MediaType.APPLICATION_JSON)
    public Response logout(@CookieParam("session") String sessionCookieIn) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        Response.ResponseBuilder rb = Response.ok(body);
        if (sessionCookieIn != null) {
            rb.header("Set-Cookie",
                    "session=null; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT; httponly; samesite=lax"
                            + (cookieSecure ? "; secure" : ""));
        }
        return rb.build();
    }

    /** anthropic_configured: bool(anthropic_api_key). v2's key defaults to the
     * "no-key" boot sentinel when ANTHROPIC_API_KEY is unset — treat that (and a
     * blank) as not configured. */
    private boolean anthropicConfigured() {
        return anthropicKey.map(k -> !k.isBlank() && !"no-key".equals(k)).orElse(false);
    }
}
