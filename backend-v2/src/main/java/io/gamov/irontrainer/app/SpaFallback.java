package io.gamov.irontrainer.app;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.io.InputStream;

/**
 * SPA client-side-routing fallback. The React app uses BrowserRouter, so a
 * deep-link refresh (e.g. GET /today, /plan) reaches the server with no matching
 * static file and no API route, throwing NotFoundException. FastAPI served the
 * shell for these via StaticFiles(directory=frontend/dist, html=True); this
 * replicates that so backend-v2 is the whole front door.
 *
 * This mapper is registered for the WHOLE app, so it also intercepts intentional
 * NotFoundExceptions thrown by /api endpoints (e.g. "No active plan to export").
 * It must be TRANSPARENT for everything that isn't a client route: only a GET/HEAD
 * to a non-api, non-asset path is treated as a SPA deep link and answered with
 * index.html. Every other case passes the original exception's response through
 * unchanged (e.getResponse()) — byte-identical to how the request would render if
 * this mapper weren't registered, preserving API 404 parity with FastAPI. Static
 * assets (/, /assets/*, /index.html) are served by Quarkus before JAX-RS, so real
 * files never reach here at all.
 */
@Provider
public class SpaFallback implements ExceptionMapper<NotFoundException> {

    private static volatile byte[] indexHtml;

    @Context
    UriInfo uriInfo;

    @Context
    Request request;

    @Override
    public Response toResponse(NotFoundException e) {
        // /privacy must NOT fall through to the SPA. It is a legal document linked
        // from OAuth consent screens and opened cold by app reviewers, and the SPA
        // shows a LOGIN SCREEN when auth is required — the worst possible thing to
        // serve at that URL. Redirect to the static file, which is readable
        // unauthenticated. Extensionless /privacy is the URL worth publishing;
        // privacy.html is what actually exists on disk.
        if (isPrivacyRoute()) {
            return Response.status(Response.Status.MOVED_PERMANENTLY)
                    .location(java.net.URI.create("/privacy.html"))
                    .build();
        }
        if (isClientRoute()) {
            byte[] html = index();
            if (html != null) {
                return Response.ok(html)
                        .type("text/html; charset=utf-8")
                        .header("Cache-Control", "no-cache")
                        .build();
            }
        }
        // Not a SPA deep link (API/asset/non-GET path, or index.html unreadable):
        // pass the original 404 through untouched — exact parity with the
        // no-mapper default, including any entity the endpoint attached.
        return e.getResponse();
    }

    /** GET/HEAD to exactly "privacy" (no extension) — see toResponse. */
    private boolean isPrivacyRoute() {
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }
        String path = uriInfo.getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return "privacy".equals(path);
    }

    /**
     * A GET/HEAD to a path that is neither an API route (api/*), a Quarkus
     * non-application endpoint (q/*), nor an asset-looking path (contains a dot).
     * StaticFiles(html=True) only served the shell for GET/HEAD, so other verbs
     * fall through to their real 404/405.
     */
    private boolean isClientRoute() {
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }
        String path = uriInfo.getPath();
        while (path.startsWith("/")) { // normalize: getPath() may include a leading slash
            path = path.substring(1);
        }
        return !path.startsWith("api/") && !path.startsWith("q/") && !path.contains(".");
    }

    private static byte[] index() {
        byte[] cached = indexHtml;
        if (cached != null) {
            return cached;
        }
        try (InputStream in = SpaFallback.class.getResourceAsStream("/META-INF/resources/index.html")) {
            if (in == null) {
                return null;
            }
            cached = in.readAllBytes();
            indexHtml = cached;
            return cached;
        } catch (Exception ex) {
            return null;
        }
    }
}
