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
    @Inject
    CurrentAthlete current;

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

    /**
     * Resolve the athlete for an Apple sign-in, with account **linking**:
     * 1. Already linked to this Apple id → that athlete (returning user).
     * 2. Otherwise, if the request is authenticated (e.g. via a Strava session/
     *    bearer) and that athlete has no Apple id yet → LINK the Apple id to the
     *    current athlete, so Strava + Apple are one account.
     * 3. Otherwise → a fresh Strava-free account.
     *
     * (Merging two pre-existing accounts — you're signed in as A but sign in with
     * an Apple id already owned by B — is out of scope; case 1 wins, returning B.
     * The reverse link, adding Strava to an Apple-first account, is bean 4uj1.)
     */
    private Athlete findOrCreate(AppleAuth.AppleId apple) {
        Athlete linked = Athlete.find("appleUserId", apple.sub()).firstResult();
        if (linked != null) {
            return linked;
        }
        Integer cur = current.idOrNull();
        if (cur != null) {
            Athlete a = Athlete.findById(cur);
            if (a != null && a.appleUserId == null) {
                a.appleUserId = apple.sub();
                a.persist();
                LOG.infof("Linked Apple id to existing athlete %d.", a.id);
                return a;
            }
        }
        Athlete a = new Athlete();
        a.appleUserId = apple.sub();
        a.persist();
        LOG.infof("Created athlete %d via Sign in with Apple.", a.id);
        return a;
    }
}
