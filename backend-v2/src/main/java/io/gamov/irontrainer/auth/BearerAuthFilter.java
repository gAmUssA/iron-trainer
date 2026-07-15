package io.gamov.irontrainer.auth;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Resolves the athlete for the request, mirroring the FastAPI middleware:
 * Bearer token (SHA-256 hash → device_token row) wins when present; with
 * auth not required (local/dev), fall back to the default athlete (id 1).
 * Session-cookie auth arrives in Phase 7 — until then cookie-only clients
 * stay on the FastAPI side of the strangler. */
@Provider
public class BearerAuthFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(BearerAuthFilter.class);

    @Inject
    CurrentAthlete current;

    // Read at request time, NOT field-injected: a @Provider is instantiated
    // during native-image static init, which bakes the build-time value and
    // makes Quarkus refuse to start when the runtime value differs
    // (observed on Railway: baked false vs runtime true).
    private static boolean authRequired() {
        return ConfigProvider.getConfig()
                .getOptionalValue("irontrainer.auth-required", Boolean.class)
                .orElse(false);
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        String authz = ctx.getHeaderString("Authorization");
        if (authz != null && authz.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String hash = sha256(authz.substring(7).strip());
            DeviceToken t = DeviceToken.find("tokenHash", hash).firstResult();
            if (t != null) {
                current.set(t.athleteId);
                LOG.debugf("Bearer resolved: athlete=%d device=%s", t.athleteId, t.name);
                return;
            }
            // Unknown token: log the event, never the credential.
            LOG.infof("Bearer rejected: unknown token (path=%s)",
                    ctx.getUriInfo().getPath());
        }
        if (!authRequired()) {
            current.set(1); // single-athlete local mode, same as FastAPI default
        }
        // else: leave unset — CurrentAthlete.require() raises 401 on use.
    }

    public static String sha256(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
