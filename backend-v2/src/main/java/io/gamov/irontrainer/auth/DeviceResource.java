package io.gamov.irontrainer.auth;

import io.gamov.irontrainer.util.PyJson;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.logging.Logger;

/** Device pairing — the native-app login (bearer-token minting). Port of
 * auth_router's device endpoints. */
@Path("/api/device")
public class DeviceResource {

    private static final Logger LOG = Logger.getLogger(DeviceResource.class);

    @Inject
    CurrentAthlete current;

    @Inject
    Devices devices;

    @Inject
    ClaimThrottle throttle;

    /** POST /api/device/pairing-code — mint a short-lived code (web UI shows a QR).
     * Optional {name}. Local no-login mode pairs to the default athlete. */
    @POST
    @Path("/pairing-code")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> pairingCode(String rawBody) {
        // PairingCodeRequest | None: an absent body is fine; a present body must be
        // a valid object with a string-or-null name (422 otherwise). Validate
        // before the auth check (pydantic 422 precedes the handler's 401).
        Map<String, Object> body = parseOptionalObject(rawBody);
        String name = null;
        if (body != null) {
            Object n = body.get("name");
            if (n != null && !(n instanceof String)) {
                throw new WebApplicationException(422);
            }
            name = (String) n;
        }
        int aid = current.require();   // 401 when auth required and not logged in
        Map<String, Object> out = devices.createPairingCode(aid, name);
        LOG.infof("Issued device pairing code for athlete %d (expires %s).", aid, out.get("expires_at"));
        return out;
    }

    /** POST /api/device/ingest-token — mint a bearer for the Health-Auto-Export
     * automation. An ingest token must NOT mint siblings (a leaked push credential
     * stays a push credential) → 403. Shown once; only the hash is stored. */
    @POST
    @Path("/ingest-token")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> ingestToken(@HeaderParam("Authorization") String authz) {
        int aid = current.require();
        if (authz != null && authz.regionMatches(true, 0, "Bearer ", 0, 7)
                && Devices.INGEST_TOKEN_NAME.equals(devices.bearerTokenName(authz.substring(7).strip()))) {
            throw new WebApplicationException("Ingest tokens cannot mint new tokens.", 403);
        }
        String token = devices.createBearerToken(Devices.INGEST_TOKEN_NAME, aid);
        LOG.infof("Issued health-ingest token for athlete %d.", aid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("header", "Bearer " + token);
        out.put("path", "/api/health/ingest");
        return out;
    }

    /** POST /api/device/claim — exchange a pairing code for a long-lived bearer
     * token. Unauthenticated (the code is the credential); throttled per client. */
    @POST
    @Path("/claim")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> claim(String rawBody,
                                     @HeaderParam("X-Forwarded-For") String xff,
                                     @Context HttpServerRequest request) {
        // Validate the body FIRST — FastAPI's pydantic ClaimRequest 422 precedes the
        // endpoint (and thus the throttle). code is required; device_name is str|None
        // (a non-string is rejected, not silently dropped).
        Map<String, Object> body = parseRequiredObject(rawBody);
        if (!(body.get("code") instanceof String code)) {
            throw new WebApplicationException(422);
        }
        Object dn = body.get("device_name");
        if (dn != null && !(dn instanceof String)) {
            throw new WebApplicationException(422);
        }
        String deviceName = (String) dn;
        String client = clientKey(xff, request);
        if (throttle.throttled(client)) {
            throw new WebApplicationException("Too many attempts — try again in a minute.", 429);
        }
        Map<String, Object> result = devices.claimPairingCode(code.strip(), deviceName);
        if (result == null) {
            throttle.recordFailure(client);
            LOG.warn("Rejected device claim with an invalid/expired pairing code.");
            throw new WebApplicationException("Invalid or expired pairing code.", 400);
        }
        // A fat-fingered code followed by the right one shouldn't self-lock.
        throttle.clear(client);
        LOG.infof("Device %s paired.", deviceName == null ? "(unnamed)" : deviceName);
        return result;
    }

    /** DELETE /api/device/tokens — revoke all paired devices for the athlete. */
    @DELETE
    @Path("/tokens")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> revokeTokens() {
        int aid = current.require();
        int n = devices.revokeTokens(aid);
        LOG.infof("Athlete %d revoked %d device token(s).", aid, n);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revoked", n);
        return out;
    }

    /** _client_key: the first X-Forwarded-For hop when present (Railway runs behind
     * a proxy), else the socket host. XFF is spoofable, but that only spreads an
     * attacker across throttle keys — friction, not the security boundary. */
    private static String clientKey(String xff, HttpServerRequest request) {
        // Python `if xff:` — a present-but-whitespace header is truthy (keys on the
        // stripped-empty first hop), so match with isEmpty (not isBlank).
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].strip();
        }
        SocketAddress addr = request == null ? null : request.remoteAddress();
        return addr != null ? addr.host() : "unknown";
    }

    /** A present body must be a JSON object; malformed/non-object → 422 (pydantic).
     * Null when the body is absent (an optional model). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseOptionalObject(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        Object parsed;
        try {
            parsed = PyJson.loads(rawBody);
        } catch (RuntimeException e) {
            throw new WebApplicationException(422);   // malformed JSON
        }
        if (!(parsed instanceof Map)) {
            throw new WebApplicationException(422);   // not an object
        }
        return (Map<String, Object>) parsed;
    }

    /** Like parseOptionalObject but the body is required (absent → 422). */
    private static Map<String, Object> parseRequiredObject(String rawBody) {
        Map<String, Object> m = parseOptionalObject(rawBody);
        if (m == null) {
            throw new WebApplicationException(422);
        }
        return m;
    }
}
