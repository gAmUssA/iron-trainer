package io.gamov.irontrainer.auth;

import io.gamov.irontrainer.athlete.Athlete;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
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
    public Map<String, Object> signIn(AppleSignInRequest req,
                                      @HeaderParam("Authorization") String authorization) {
        if (req == null || req.identityToken() == null || req.identityToken().isBlank()) {
            throw new WebApplicationException("Missing identityToken.", 400);
        }
        AppleAuth.AppleId apple = appleAuth.verify(req.identityToken());
        Athlete athlete = resolveAthlete(apple, authorization);
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
     * 1. Already bound to this Apple id → that athlete (returning user).
     * 2. Else if the request carries a genuine LOGIN bearer (see linkTarget) whose
     *    athlete has no Apple id → LINK the Apple id to it, so Strava + Apple are
     *    one account.
     * 3. Else → a fresh Strava-free account.
     *
     * Linking is deliberately gated on a real login bearer, NOT current.idOrNull():
     * with auth-required off, BearerAuthFilter falls the current athlete back to the
     * default (id 1), which would otherwise let an anonymous sign-in hijack the owner
     * account. (Merging two pre-existing accounts is out of scope — case 1 wins. The
     * reverse link, adding Strava to an Apple-first account, is bean 4uj1.)
     */
    private Athlete resolveAthlete(AppleAuth.AppleId apple, String authorization) {
        Athlete linked = Athlete.find("appleUserId", apple.sub()).firstResult();
        if (linked != null) {
            return linked;
        }
        Athlete target = linkTarget(authorization);
        if (target != null && target.appleUserId == null) {
            target.appleUserId = apple.sub();
            target.persist();
            LOG.infof("Linked Apple id to existing athlete %d.", target.id);
            return target;
        }
        return create(apple);
    }

    /**
     * The athlete an Apple sign-in may link to — only when the request presents a
     * genuine, login-capable bearer: an actual `Authorization: Bearer` header (so the
     * no-auth default-athlete fallback never links), a token that resolves to a real
     * name (not a revoked/unknown token), and NOT a scoped ingest token (which, like
     * device minting, must not escalate). Returns null → treat as a fresh sign-in.
     */
    private Athlete linkTarget(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String tokenName = devices.bearerTokenName(authorization.substring(7).trim());
        if (tokenName == null || Devices.INGEST_TOKEN_NAME.equals(tokenName)) {
            return null;
        }
        Integer cur = current.idOrNull();   // the athlete this same bearer resolved to
        return cur == null ? null : Athlete.findById(cur);
    }

    /** Create a Strava-free account. `persistAndFlush` surfaces the apple_user_id
     * unique-index violation from a concurrent double-tap here (rather than at commit),
     * so we can answer 409 — the client retries and finds the athlete the winning
     * request created. Rate-limiting is unnecessary: creation requires a valid,
     * Apple-signed token (not a guessable code like /api/device/claim). */
    private Athlete create(AppleAuth.AppleId apple) {
        try {
            Athlete a = new Athlete();
            a.appleUserId = apple.sub();
            a.persistAndFlush();
            LOG.infof("Created athlete %d via Sign in with Apple.", a.id);
            return a;
        } catch (PersistenceException e) {
            throw new WebApplicationException("Concurrent Apple sign-in — retry.", 409);
        }
    }
}
