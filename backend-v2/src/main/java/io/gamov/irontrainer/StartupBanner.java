package io.gamov.irontrainer;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** One-line config summary on boot, mirroring the FastAPI startup line
 * ("... starting — db=..., auth_required=..."). Whatever config a Railway
 * deploy actually came up with is the first thing worth seeing in the logs. */
@ApplicationScoped
public class StartupBanner {

    private static final Logger LOG = Logger.getLogger(StartupBanner.class);

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "?")
    String version;

    @ConfigProperty(name = "quarkus.datasource.db-kind", defaultValue = "?")
    String dbKind;

    void onStart(@Observes StartupEvent ev) {
        // auth-required read at runtime (not field-injected) — same reason as
        // BearerAuthFilter: avoid baking a build-time value into native image.
        boolean authRequired = ConfigProvider.getConfig()
                .getOptionalValue("irontrainer.auth-required", Boolean.class)
                .orElse(false);
        String sessionSecret = ConfigProvider.getConfig()
                .getOptionalValue("irontrainer.session-secret", String.class)
                .orElse("");
        // Fail-fast when auth is required but no session secret is set: without
        // it, every valid FastAPI-signed cookie silently fails to verify (401).
        // Mirrors FastAPI's enforce_secure_config, which refuses to boot in the
        // same misconfiguration.
        if (authRequired && sessionSecret.isBlank()) {
            throw new IllegalStateException(
                    "SESSION_SECRET must be set when auth is required — cookie "
                    + "verification cannot work without the shared signing secret.");
        }
        LOG.infof("Iron Trainer backend-v2 %s ready — db=%s auth_required=%s",
                version, dbKind, authRequired);
    }
}
