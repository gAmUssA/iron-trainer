package io.gamov.irontrainer.app;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * Canonical-host redirect (bean 3e6w): 301s the {@code www.} variant to the bare
 * canonical host so the whole app lives on ONE origin. The 3-domain setup
 * (irontrainer.app, www.irontrainer.app, iron-trainer.up.railway.app) with
 * host-scoped session cookies is a footgun — a user logged in on one host acts
 * anonymously on another (the Sign-in-with-Apple linking dance came from exactly
 * this). Collapsing to a single origin gives one cookie + one Apple Return URL.
 *
 * <p>Registered as a Vert.x route (order -1000) so it runs BEFORE static SPA
 * serving and JAX-RS — the initial {@code www} page load itself redirects, rather
 * than the SPA loading and its {@code /api} calls redirecting cross-origin.
 *
 * <p>Gated on {@code irontrainer.canonical-host} (env {@code CANONICAL_HOST}); unset
 * (dev/test) = no route registered. Only the {@code www.} variant is redirected —
 * the apex serves normally and the Railway {@code *.up.railway.app} host is left
 * alone so its startup health check keeps returning 200.
 */
@ApplicationScoped
public class CanonicalHostRoute {

    private static final Logger LOG = Logger.getLogger(CanonicalHostRoute.class);

    // Read via ConfigProvider (not @ConfigProperty field injection) to get the
    // runtime value under native image — same reason as BearerAuthFilter. Observing
    // Router (fired at startup) is Quarkus' documented way to add routes.
    void register(@Observes Router router) {
        String canonical = ConfigProvider.getConfig()
                .getOptionalValue("irontrainer.canonical-host", String.class)
                .filter(s -> !s.isBlank())
                .orElse(null);
        if (canonical == null) {
            return;
        }
        router.route().order(-1000).handler(rc -> {
            String target = redirectTarget(canonical, rc.request().getHeader("Host"), rc.request().uri());
            if (target != null) {
                rc.response().setStatusCode(301).putHeader("Location", target).end();
            } else {
                rc.next();
            }
        });
        LOG.infof("Canonical-host redirect active: www.%s -> %s", canonical, canonical);
    }

    /**
     * The canonical redirect Location for a request, or {@code null} if none applies.
     * Only the {@code www.<canonical>} host redirects (port stripped, case-insensitive);
     * the apex and any other host (e.g. the Railway health-check host) pass through.
     * Static + package-private so the decision is unit-testable without HTTP.
     */
    static String redirectTarget(String canonical, String hostHeader, String uri) {
        if (canonical == null || canonical.isBlank() || hostHeader == null) {
            return null;
        }
        String host = hostHeader.split(":", 2)[0].toLowerCase();
        if (!host.equals("www." + canonical.toLowerCase())) {
            return null;
        }
        return "https://" + canonical + uri;
    }
}
