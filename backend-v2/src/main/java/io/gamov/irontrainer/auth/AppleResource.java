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
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Sign in with Apple (bean 3e6w). Two entry points share the same verify +
 * find-or-create/link logic:
 *   POST /api/auth/apple      — NATIVE (iOS): identity token → device bearer.
 *   POST /api/auth/apple/web  — WEB (Sign in with Apple JS): id_token → session
 *                               cookie (mirrors the Strava web login).
 * The response athlete shape matches /api/device/claim so existing clients are
 * unchanged. Account linking (Strava + Apple = one account) is gated on a genuine
 * credential — never the no-auth default-athlete fallback (see linkTarget/*).
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

    @ConfigProperty(name = "irontrainer.session-secret")
    Optional<String> sessionSecret;
    @ConfigProperty(name = "irontrainer.cookie-secure")
    boolean cookieSecure;

    public record AppleSignInRequest(String identityToken, String deviceName) {}

    // ── Native (iOS): identity token → device bearer ──────────────────────────
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Map<String, Object> signIn(AppleSignInRequest req,
                                      @HeaderParam("Authorization") String authorization) {
        AppleAuth.AppleId apple = verify(req);
        Athlete athlete = resolveAthlete(apple, bearerLinkTarget(authorization));
        String name = (req.deviceName() == null || req.deviceName().isBlank())
                ? "apple-signin" : req.deviceName();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", devices.createBearerToken(name, athlete.id));
        out.put("athlete", athleteBody(athlete));
        return out;
    }

    // ── Web (Sign in with Apple JS): id_token → session cookie ────────────────
    @POST
    @Path("/web")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response signInWeb(AppleSignInRequest req, @HeaderParam("Cookie") String cookieHeader) {
        AppleAuth.AppleId apple = verify(req);
        Athlete athlete = resolveAthlete(apple, sessionLinkTarget(cookieHeader));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("athlete", athleteBody(athlete));
        return Response.ok(body).header("Set-Cookie", sessionCookie(athlete.id)).build();
    }

    // ── shared ────────────────────────────────────────────────────────────────

    private AppleAuth.AppleId verify(AppleSignInRequest req) {
        if (req == null || req.identityToken() == null || req.identityToken().isBlank()) {
            throw new WebApplicationException("Missing identityToken.", 400);
        }
        return appleAuth.verify(req.identityToken());
    }

    private Map<String, Object> athleteBody(Athlete a) {
        Map<String, Object> ath = new LinkedHashMap<>();
        ath.put("name", a.name);
        ath.put("strava_athlete_id", a.stravaAthleteId);
        return ath;
    }

    /**
     * Resolve the athlete for this Apple sign-in.
     *
     * AUTHENTICATED (linkTarget != null — a real bearer/session was presented): this is
     * a LINK, never an account switch. It must resolve to `linkTarget` or fail 409 — we
     * must never mint a bearer / Set-Cookie for a *different* athlete, which would
     * silently strand the caller's real (e.g. Strava) account and its data. So: already
     * linked to this same athlete → idempotent; the Apple id belongs to someone else, or
     * this athlete already has a different Apple id → 409 conflict; otherwise link.
     *
     * UNAUTHENTICATED (linkTarget == null): log in to the Apple-bound athlete, else
     * create a fresh Strava-free one. linkTarget is null unless a real credential was
     * presented (see bearerLinkTarget/sessionLinkTarget), so the no-auth default-athlete
     * fallback can never be hijacked. Merging two pre-existing accounts is out of scope;
     * the reverse link (Strava onto an Apple account) is 4uj1.
     */
    private Athlete resolveAthlete(AppleAuth.AppleId apple, Athlete linkTarget) {
        Athlete linked = Athlete.find("appleUserId", apple.sub()).firstResult();

        if (linkTarget != null) {
            if (linked != null) {
                if (!linked.id.equals(linkTarget.id)) {
                    throw new WebApplicationException(
                            "This Apple account is already linked to a different account.", 409);
                }
                return linked; // already linked to this same athlete — idempotent
            }
            if (linkTarget.appleUserId != null) {
                throw new WebApplicationException(
                        "This account is already linked to a different Apple account.", 409);
            }
            linkTarget.appleUserId = apple.sub();
            linkTarget.persist();
            LOG.infof("Linked Apple id to existing athlete %d.", linkTarget.id);
            return linkTarget;
        }

        return linked != null ? linked : create(apple);
    }

    /** Link target for the NATIVE flow: only when a real, login-capable bearer is
     * present — an actual `Authorization: Bearer` header (so the default-athlete
     * fallback never links), resolving to a known token that is NOT a scoped ingest
     * token (which must not escalate). Null → fresh sign-in. */
    private Athlete bearerLinkTarget(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String tokenName = devices.bearerTokenName(authorization.substring(7).trim());
        if (tokenName == null || Devices.INGEST_TOKEN_NAME.equals(tokenName)) {
            return null;
        }
        Integer cur = current.idOrNull();
        return cur == null ? null : Athlete.findById(cur);
    }

    /** Link target for the WEB flow: only when a valid, signed session cookie is
     * present (a genuinely logged-in web user, e.g. via Strava). Resolving the athlete
     * straight from the cookie signature avoids the default-athlete fallback. */
    private Athlete sessionLinkTarget(String cookieHeader) {
        String secret = sessionSecret.filter(s -> !s.isBlank()).orElse(null);
        if (cookieHeader == null || secret == null) {
            return null;
        }
        // On duplicate session= cookies the LAST one wins, matching
        // BearerAuthFilter.sessionCookieValue (http.cookies semantics) — so linking
        // resolves the same athlete the rest of the app authenticates the request as.
        String value = null;
        for (String part : cookieHeader.split(";")) {
            String p = part.trim();
            if (p.startsWith("session=")) {
                value = p.substring("session=".length());
            }
        }
        if (value == null) {
            return null;
        }
        Integer aid = SessionCookie.athleteId(value, secret);
        return aid == null ? null : Athlete.findById(aid);
    }

    private String sessionCookie(int athleteId) {
        String secret = sessionSecret.filter(s -> !s.isBlank())
                .orElseThrow(() -> new WebApplicationException("Server not configured for web login.", 500));
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("athlete_id", athleteId);
        String header = "session=" + SessionCookie.sign(session, secret)
                + "; path=/; Max-Age=" + SessionCookie.MAX_AGE_SECONDS + "; httponly; samesite=lax";
        return cookieSecure ? header + "; secure" : header;
    }

    /** Create a Strava-free account. `persistAndFlush` surfaces the apple_user_id
     * unique-index violation from a concurrent double-tap here (not at commit), so we
     * answer 409 — the client retries and finds the winner's athlete. No rate-limit
     * needed: creation requires a valid Apple-signed token (not a guessable code). */
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
