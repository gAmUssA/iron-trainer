package io.gamov.irontrainer.admin;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;

/** 401s any @RequireAdmin endpoint without a valid admin_session cookie (bean gfb3).
 * Runs at AUTHENTICATION priority so it gates before the resource method. */
@Provider
@RequireAdmin
@Priority(Priorities.AUTHENTICATION)
public class AdminAuthFilter implements ContainerRequestFilter {

    // Read at request time (ConfigProvider), not @ConfigProperty field injection — a
    // @Provider is instantiated during native-image static init, which would bake the
    // build-time value (same reason as BearerAuthFilter).
    private static String sessionSecret() {
        return ConfigProvider.getConfig()
                .getOptionalValue("irontrainer.session-secret", String.class)
                .orElse("");
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!AdminSession.isValid(ctx.getHeaderString("Cookie"), sessionSecret())) {
            ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "admin authentication required"))
                    .build());
        }
    }
}
