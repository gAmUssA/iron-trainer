package io.gamov.irontrainer.app;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.io.InputStream;

/**
 * SPA client-side-routing fallback. The React app uses BrowserRouter, so a
 * deep-link refresh (e.g. /today, /plan) reaches the server as a GET with no
 * matching static file and no API route. FastAPI handled this implicitly via
 * StaticFiles(directory=frontend/dist, html=True), which serves index.html for
 * any unmatched path; this replicates that so backend-v2 is the whole front door.
 *
 * Static files under META-INF/resources are served by Quarkus BEFORE JAX-RS, so
 * real assets (/, /assets/*, /index.html) never reach here — only truly
 * unmatched paths throw NotFoundException. For those we return index.html and
 * let the client router take over. Genuine 404s are preserved for API paths
 * (api/*, q/*) and asset-looking paths (containing a dot), matching what a real
 * missing file/endpoint should return.
 */
@Provider
public class SpaFallback implements ExceptionMapper<NotFoundException> {

    private static volatile byte[] indexHtml;

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(NotFoundException e) {
        String path = uriInfo.getPath();
        while (path.startsWith("/")) { // normalize: getPath() may include a leading slash
            path = path.substring(1);
        }
        if (path.startsWith("api/") || path.startsWith("q/") || path.contains(".")) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        byte[] html = index();
        if (html == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(html)
                .type("text/html; charset=utf-8")
                .header("Cache-Control", "no-cache")
                .build();
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
