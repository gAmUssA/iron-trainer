package io.gamov.irontrainer.auth;

import io.gamov.irontrainer.athlete.Athlete;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sign in with Apple → mint a device bearer for a Strava-free account (bean 3e6w).
 * The iOS app presents Apple's identity token here; we verify it, find-or-create
 * the athlete by the stable Apple id, and return a bearer in the same shape as
 * /api/device/claim so the app's existing session handling is unchanged.
 */
@Path("/api/auth/apple")
public class AppleResource {
    private static final Logger LOG = Logger.getLogger(AppleResource.class);

    @Inject
    AppleAuth appleAuth;
    @Inject
    Devices devices;

    public record AppleSignInRequest(String identityToken, String deviceName) {}

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Map<String, Object> signIn(AppleSignInRequest req) {
        if (req == null || req.identityToken() == null || req.identityToken().isBlank()) {
            throw new WebApplicationException("Missing identityToken.", 400);
        }
        AppleAuth.AppleId apple = appleAuth.verify(req.identityToken());
        Athlete athlete = findOrCreate(apple);
        String name = (req.deviceName() == null || req.deviceName().isBlank())
                ? "apple-signin" : req.deviceName();
        String token = devices.createBearerToken(name, athlete.id);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        Map<String, Object> ath = new LinkedHashMap<>();
        ath.put("name", athlete.name);
        ath.put("strava_athlete_id", athlete.stravaAthleteId);
        out.put("athlete", ath);
        return out;
    }

    /** find-or-create by the stable Apple `sub`. Apple gives email/name only on the
     * first authorization; we store nothing sensitive by default (name is set later
     * in the profile), mirroring the Strava find-or-create. */
    private Athlete findOrCreate(AppleAuth.AppleId apple) {
        Athlete a = Athlete.find("appleUserId", apple.sub()).firstResult();
        if (a == null) {
            a = new Athlete();
            a.appleUserId = apple.sub();
            a.persist();
            LOG.infof("Created athlete %d via Sign in with Apple.", a.id);
        }
        return a;
    }
}
