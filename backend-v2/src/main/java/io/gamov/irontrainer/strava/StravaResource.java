package io.gamov.irontrainer.strava;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.auth.DeviceToken;
import io.gamov.irontrainer.auth.SessionCookie;
import io.gamov.irontrainer.jobs.JobRunner;
import io.gamov.irontrainer.metrics.MetricDaily;
import io.gamov.irontrainer.metrics.MetricsWrite;
import io.gamov.irontrainer.util.Params;
import io.gamov.irontrainer.util.PyJson;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

/** Strava vertical: OAuth connect + callback (login) + disconnect (bean xtre)
 * plus de-duplication + activity sync. The GDPR archive import is a separate
 * slice (bean f6ui). */
@Path("/api/strava")
public class StravaResource {

    private static final Logger LOG = Logger.getLogger(StravaResource.class);

    @Inject
    CurrentAthlete current;

    @Inject
    StravaSync sync;

    @Inject
    StravaTokens tokens;

    @Inject
    DedupService dedupService;

    @Inject
    JobRunner jobs;

    @Inject
    StravaOAuth oauth;

    // Optional: ${SESSION_SECRET:} defaults to empty, which SmallRye's String
    // converter treats as null (a required @ConfigProperty would fail boot).
    @ConfigProperty(name = "irontrainer.session-secret")
    Optional<String> sessionSecret;

    @ConfigProperty(name = "irontrainer.cookie-secure")
    boolean cookieSecure;

    // settings.default_athlete_id — the identity used in local no-login mode
    // (callback's non-auth branch attaches the tokens to this athlete).
    @ConfigProperty(name = "irontrainer.default-athlete-id")
    int defaultAthleteId;

    /** GET /api/strava/connect — begin Strava OAuth (also the login entry point).
     * Mints a session cookie carrying the CSRF oauth_state and 307-redirects to
     * Strava's consent screen. Preserves any existing session (athlete_id) so a
     * logged-in user who cancels at Strava stays logged in. Port of
     * strava_router.connect. */
    @GET
    @Path("/connect")
    public Response connect(@CookieParam("session") String sessionCookieIn) {
        if (!oauth.configured()) {
            throw new BadRequestException("Strava client ID/secret not configured in .env");
        }
        String secret = sessionSecret.filter(s -> !s.isBlank()).orElse(null);
        if (secret == null) {
            // Fail loud, not silent: a blank secret mints a cookie neither backend
            // can verify → a total, invisible login outage. (Prod has SESSION_SECRET.)
            throw new InternalServerErrorException("SESSION_SECRET not configured");
        }
        String state = StravaOAuth.newState();
        // Mutate the EXISTING session (like request.session[...]=state), preserving
        // athlete_id; start fresh only when there's no valid session cookie.
        Map<String, Object> existing = SessionCookie.read(sessionCookieIn, secret);
        Map<String, Object> session = existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
        session.put("oauth_state", state);
        LOG.debug("Strava OAuth connect: minted oauth_state, redirecting to Strava.");
        return Response.temporaryRedirect(URI.create(oauth.authorizeUrl(state)))
                .header("Set-Cookie", setCookieHeader(session, secret)).build();
    }

    /** GET /api/strava/callback — the OAuth redirect target (LOGIN happens here).
     * Port of strava_router.callback:
     *  - denied/no-code → SPA redirect with strava_error;
     *  - auth mode: verify oauth_state (CSRF), exchange the code, allowlist-gate,
     *    find-or-create the athlete, mint an athlete_id LOGIN session, save tokens;
     *  - local mode: attach tokens to the default athlete (no new users).
     * All outcomes 307-redirect back to the SPA with a query flag. */
    @GET
    @Path("/callback")
    public Response callback(@QueryParam("code") String code,
                             @QueryParam("state") String state,
                             @QueryParam("error") String error,
                             @CookieParam("session") String sessionCookieIn) {
        // Python truthiness parity: `if error or not code` — an empty-string error
        // is NOT an error, and an empty-string code counts as no code.
        boolean hasError = error != null && !error.isEmpty();
        boolean hasCode = code != null && !code.isEmpty();
        if (hasError || !hasCode) {
            // Strava bounced the user back denied/cancelled (e.g. access_denied).
            LOG.infof("Strava authorization returned without a code (error=%s).", error);
            return redirect("strava_error", hasError ? error : "no_code").build();
        }
        String secret = sessionSecret.filter(s -> !s.isBlank()).orElse(null);
        Map<String, Object> existing = secret == null ? null : SessionCookie.read(sessionCookieIn, secret);
        Map<String, Object> session = existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
        boolean authRequired = oauth.authRequired();
        if (authRequired) {
            Object savedState = session.get("oauth_state");
            if (state == null || !state.equals(savedState)) {
                // Returns BEFORE consuming oauth_state — FastAPI does not pop here,
                // so no session mutation → no Set-Cookie (parity).
                LOG.warn("Strava callback with invalid OAuth state.");
                return redirect("strava_error", "invalid_state").build();
            }
        }
        // Consume oauth_state. FastAPI pops it here, so Starlette re-emits the
        // (mutated) session cookie on EVERY later return — exchange_failed,
        // not_allowed, local success, and auth success. Mirror that: whenever we
        // actually removed an oauth_state, carry a cleared-session Set-Cookie.
        boolean consumedState = session.remove("oauth_state") != null;
        String clearedCookie = (consumedState && secret != null) ? setCookieHeader(session, secret) : null;

        Map<String, Object> token;
        try {
            token = oauth.exchangeCode(code);
        } catch (WebApplicationException | ProcessingException e) {   // httpx.HTTPError parity
            LOG.warnf("Strava token exchange failed: %s", e.toString());
            return withCookie(redirect("strava_error", "exchange_failed"), clearedCookie);
        }
        Object athObj = token.get("athlete");
        Map<?, ?> ath = athObj instanceof Map<?, ?> m ? m : Map.of();
        Long stravaId = ath.get("id") instanceof Number n ? n.longValue() : null;

        int athleteId;
        if (authRequired) {
            if (stravaId == null || !oauth.isAllowed(stravaId)) {
                LOG.warnf("Rejected Strava login for athlete id %s (not on allowlist).", stravaId);
                return withCookie(redirect("strava_error", "not_allowed"), clearedCookie);
            }
            athleteId = persistLogin(stravaId, token);   // find-or-create + save tokens (name too)
            session.put("athlete_id", athleteId);
            // Mint the athlete_id LOGIN session (oauth_state already consumed).
            return withCookie(redirect("connected", "1"), setCookieHeader(session, secret));
        }
        // Local single-user mode: attach to the default athlete (no new users).
        athleteId = defaultAthleteId;
        persistTokens(athleteId, token);
        LOG.infof("Strava connected to local default athlete (strava id %s).", stravaId);
        return withCookie(redirect("connected", "1"), clearedCookie);
    }

    /** Build a redirect Response, attaching a Set-Cookie only when one is present
     * (mirrors Starlette emitting a cookie exactly when the session was mutated). */
    private static Response withCookie(Response.ResponseBuilder rb, String setCookie) {
        if (setCookie != null) {
            rb.header("Set-Cookie", setCookie);
        }
        return rb.build();
    }

    /** POST /api/strava/disconnect — revoke access at Strava, then purge the
     * athlete's synced activities + derived metrics + device tokens and clear the
     * stored Strava tokens/name (API agreement §7.4). Deauthorize is best-effort
     * (already-revoked/gone still purges locally). Port of strava_router.disconnect. */
    @POST
    @Path("/disconnect")
    public Map<String, Object> disconnect() {
        int aid = current.require();   // 401 when auth is required and nobody is logged in
        boolean deauthorized = false;
        try {
            oauth.deauthorize(tokens.validAccessToken(aid));   // 409 NotConnected → skip
            deauthorized = true;
        } catch (WebApplicationException | ProcessingException e) {
            // NotConnected (409) / Strava HTTP or connection error — still purge
            // local data (FastAPI: except (NotConnected, httpx.HTTPError)). An
            // unexpected error propagates → 500, matching FastAPI.
            LOG.infof("Deauthorize skipped (%s); proceeding to local deletion.", e.toString());
        }
        Map<String, Object> summary = disconnectStrava(aid);
        LOG.infof("Athlete %d disconnected Strava: deleted %s activities, %s metric days.",
                aid, summary.get("deleted_activities"), summary.get("deleted_metrics"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deauthorized", deauthorized);
        out.putAll(summary);
        out.put("message", "Disconnected. Your Strava activities and derived data have been deleted.");
        return out;
    }

    // 2 GB — well above any real Strava export (matches FastAPI MAX_UPLOAD_BYTES).
    private static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024;

    /** POST /api/strava/import — bulk-load history from a user's uploaded Strava
     * GDPR export ZIP. Athlete-scoped; works without a live API connection (no
     * rate-limit/athlete cap). Port of strava_router.import_archive.
     *
     * With ?async=1 the parse+import runs as a background job (kind "import"); an
     * import already running → 409 (unlike sync/dedup, it does NOT return the older
     * job, since a second archive would be silently discarded). */
    @POST
    @Path("/import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Map<String, Object> importArchive(@RestForm("file") FileUpload file,
                                             @QueryParam("async") String asyncParam) {
        int aid = current.require();   // 401 when auth is required and nobody is logged in
        if (file == null) {
            throw new BadRequestException("file is required");
        }
        if (file.size() > MAX_UPLOAD_BYTES) {
            throw new ClientErrorException("Archive exceeds the 2 GB upload limit.", 413);
        }
        if (Params.boolOr(asyncParam, false)) {
            // The request-scoped upload is deleted when the request ends, so copy it
            // to a job-owned temp file the background job parses and then unlinks.
            java.nio.file.Path tmp;
            try {
                tmp = Files.createTempFile("strava-import-", ".zip");
                // MOVE the already-on-disk multipart upload (avoids a second full
                // copy → ~2x temp disk for a multi-GB export); fall back to copy
                // only when the temp dirs are on different filesystems.
                try {
                    Files.move(file.uploadedFile(), tmp, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException | UnsupportedOperationException moveFailed) {
                    Files.copy(file.uploadedFile(), tmp, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new InternalServerErrorException("Could not stage the upload.");
            }
            Map<String, Object> job = jobs.submit(aid, "import", () -> {
                try {
                    return sync.runImport(aid, StravaArchive.parse(tmp));
                } finally {
                    deleteQuietly(tmp);
                }
            });
            if (Boolean.TRUE.equals(job.get("already_running"))) {
                // Our closure never ran: clean up the staged upload and say so —
                // returning the older job would silently discard this archive.
                deleteQuietly(tmp);
                throw new ClientErrorException(
                        "An import is already running — wait for it to finish, then retry.", 409);
            }
            return env(job);
        }
        try {
            return sync.runImport(aid, StravaArchive.parse(file.uploadedFile()));
        } catch (IllegalArgumentException e) {   // ValueError parity → 400
            throw new BadRequestException(e.getMessage());
        }
    }

    private static void deleteQuietly(java.nio.file.Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignore) {
            // best-effort temp cleanup
        }
    }

    /** find_or_create_athlete (by Strava id) + save_tokens, in one transaction.
     * saveTokens sets the name/tokens/expiry, so create only needs the strava id. */
    private int persistLogin(long stravaId, Map<String, Object> token) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Athlete a = Athlete.find("stravaAthleteId", stravaId).firstResult();
            if (a == null) {
                a = new Athlete();
                a.stravaAthleteId = stravaId;
                a.persist();
            }
            tokens.saveTokens(a, token);
            return a.id;
        });
    }

    /** save_tokens(athlete_id, token) for the local default athlete (non-auth). */
    private void persistTokens(int athleteId, Map<String, Object> token) {
        QuarkusTransaction.requiringNew().run(() -> {
            Athlete a = Athlete.findById(athleteId);
            if (a == null) {
                // Local mode's default athlete normally exists (seeded). If not,
                // force the id (FastAPI's save_tokens does Athlete(id=athlete_id));
                // a bare persist() would take a serial-generated id, orphaning the
                // tokens from the athlete every reader resolves to.
                Athlete.getEntityManager()
                        .createNativeQuery("INSERT INTO athlete (id) VALUES (?1)")
                        .setParameter(1, athleteId)
                        .executeUpdate();
                // Keep the id sequence ahead of the forced id — an explicit-id
                // insert doesn't advance it, so a later serial insert would collide.
                Athlete.getEntityManager()
                        .createNativeQuery("SELECT setval(pg_get_serial_sequence('athlete','id'), "
                                + "GREATEST((SELECT max(id) FROM athlete), ?1))")
                        .setParameter(1, athleteId)
                        .getSingleResult();
                a = Athlete.findById(athleteId);
            }
            tokens.saveTokens(a, token);
        });
    }

    /** disconnect_strava: delete this athlete's Strava-sourced data and clear the
     * stored tokens + Strava name. Returns the deletion summary (§2.5). */
    private Map<String, Object> disconnectStrava(int aid) {
        return QuarkusTransaction.requiringNew().call(() -> {
            long acts = Activity.delete("athleteId", aid);
            long metrics = MetricDaily.delete("athleteId", aid);
            long devices = DeviceToken.delete("athleteId", aid);
            Athlete a = Athlete.findById(aid);
            if (a != null) {
                a.stravaAccessToken = null;
                a.stravaRefreshToken = null;
                a.stravaTokenExpiresAt = null;
                a.stravaAthleteId = null;
                a.name = null;   // name came from Strava
                a.updatedAt = PyJson.utcNowIso();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("deleted_activities", (int) acts);
            out.put("deleted_metrics", (int) metrics);
            out.put("revoked_devices", (int) devices);
            return out;
        });
    }

    /** The itsdangerous-signed {@code session} Set-Cookie header value, byte-matching
     * Starlette SessionMiddleware EXACTLY: {@code session=<v>; path=/; Max-Age=1209600;
     * httponly; samesite=lax} (+ {@code ; secure} when COOKIE_SECURE). Emitted as a raw
     * header, NOT via NewCookie, because JAX-RS NewCookie quotes the value and appends
     * {@code Version=1} (RFC 2109) — which Starlette never does, so the strangler's two
     * backends would otherwise emit a different cookie for the same session. Shared by
     * connect + callback. */
    private String setCookieHeader(Map<String, Object> session, String secret) {
        String header = "session=" + SessionCookie.sign(session, secret)
                + "; path=/; Max-Age=" + SessionCookie.MAX_AGE_SECONDS + "; httponly; samesite=lax";
        return cookieSecure ? header + "; secure" : header;
    }

    /** _redirect: 307 back to the SPA ({origin}/?key=value), so OAuth outcomes
     * surface as an in-app banner. Relative "/?..." when no origin is configured
     * (matches FastAPI's empty cors_origin_list). */
    private Response.ResponseBuilder redirect(String key, String value) {
        String url = oauth.frontendOrigin() + "/?" + enc(key) + "=" + enc(value);
        return Response.temporaryRedirect(URI.create(url));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** POST /api/strava/sync — pull activity summaries from Strava, upsert, prune
     * old, de-dup, and rebuild the PMC. Port of services.run_sync. 409 when Strava
     * isn't connected. NOT @Transactional: runSync keeps the external fetch out of
     * the DB transaction (persist has its own).
     *
     * With ?async=1 the sync runs as a background job (kind "sync"); as in FastAPI
     * the connection check is NOT pre-run in async mode — a not-connected athlete
     * gets a failed job, not a 409 (only the 401 auth gate is synchronous). */
    @POST
    @Path("/sync")
    public Map<String, Object> syncActivities(@QueryParam("full") String fullParam,
                                              @QueryParam("async") String asyncParam) {
        int aid = current.require();
        // pydantic-lax bool parity: ?full=1 / ?full=yes → true (a plain JAX-RS
        // boolean coerces "1" to false).
        boolean full = Params.boolOr(fullParam, false);
        if (Params.boolOr(asyncParam, false)) {
            return env(jobs.submit(aid, "sync", () -> sync.runSync(aid, full)));
        }
        return sync.runSync(aid, full);
    }

    /** POST /api/strava/dedup — re-run de-duplication on existing activities
     * (cluster same-event → keep one → rebuild the PMC). Port of the FastAPI
     * endpoint's deduplicate(fetch_details=...) + rebuild_metrics.
     *
     * fetch=true (default) looks up device names from Strava for clustered
     * activities that lack one, so primary selection is device-aware; that needs a
     * valid token (409 when not connected, refreshed on expiry via
     * validAccessToken). fetch=false is fully local. The detail fetches run
     * OUTSIDE the DB transaction; only the mark+rebuild is transactional.
     *
     * With ?async=1 the fetch+mark+rebuild runs as a background job (kind
     * "dedup"); as in FastAPI the connection-guard 409 is acquired SYNCHRONOUSLY
     * (before the job) so a not-connected athlete still gets a 409, not a job. */
    @POST
    @Path("/dedup")
    public Map<String, Object> dedup(@QueryParam("fetch") String fetchParam,
                                     @QueryParam("limit") @DefaultValue("100") int limit,
                                     @QueryParam("async") String asyncParam) {
        int aid = current.require();
        // pydantic-lax bool parity (default true): ?fetch=0 / ?fetch=no → false,
        // ?fetch=1 → true (a plain JAX-RS boolean coerces "1" to false).
        boolean fetch = Params.boolOr(fetchParam, true);
        // Connection guard + token refresh happen here (409 when not connected) —
        // synchronously even in async mode, matching FastAPI.
        String auth = fetch ? "Bearer " + tokens.validAccessToken(aid) : null;
        if (Params.boolOr(asyncParam, false)) {
            return env(jobs.submit(aid, "dedup", () -> runDedup(aid, auth, fetch, limit)));
        }
        return runDedup(aid, auth, fetch, limit);
    }

    /** The dedup phases (external fetch outside tx; mark+rebuild in tx). Runs
     * either inline or inside a background job; takes the pre-acquired auth token
     * so the connection-guard 409 stays synchronous. */
    private Map<String, Object> runDedup(int aid, String auth, boolean fetch, int limit) {
        // Phase 1 (tx read): which clustered activities still need a device name.
        List<Long> need = fetch
                ? QuarkusTransaction.requiringNew().call(() ->
                        Dedup.clusteredNeedingDevice(loadActs(aid)))
                : List.of();
        // Phase 2 (external, no tx): fetch the missing device names. limit=0 → all
        // (FastAPI `max_fetches = limit or None`).
        DedupService.DeviceFetch df = fetch
                ? dedupService.resolveMissingDeviceNames(need, auth, limit == 0 ? null : limit)
                : new DedupService.DeviceFetch(Map.of(), 0);
        // Phase 2.5 (tx): cache fetched names before the finalize can fail.
        dedupService.persistDeviceNames(df.devices());
        // Phase 3 (tx): mark duplicates (device-aware) → rebuild the PMC.
        return QuarkusTransaction.requiringNew().call(() -> finalizeDedup(aid, df.fetched()));
    }

    /** The async response envelope: {"job": <job dict>}. */
    private static Map<String, Object> env(Map<String, Object> job) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job", job);
        return out;
    }

    /** list_activities(include_duplicates=True) — ORDER BY start_date so the input
     * order (and same-start_date tie-breaks) matches FastAPI. */
    private static List<Activity> loadActs(int aid) {
        return Activity.list("athleteId = ?1 order by startDate", aid);
    }

    private Map<String, Object> finalizeDedup(int aid, int deviceFetched) {
        List<Activity> acts = loadActs(aid);   // device names already committed (phase 2.5)
        Dedup.Result d = Dedup.markDuplicates(acts);
        int metricsDays = MetricsWrite.rebuildMetrics(aid, acts);  // reuse the loaded list
        int deviceRemaining = Dedup.countDeviceless(d.clusters());
        LOG.infof("Dedup: athlete=%d clusters=%d duplicates=%d device_fetched=%d",
                aid, d.clusters().size(), d.duplicates(), deviceFetched);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clusters", d.clusters().size());
        out.put("duplicates", d.duplicates());
        out.put("device_fetched", deviceFetched);
        out.put("device_remaining", deviceRemaining);
        out.put("metrics_days", metricsDays);
        return out;
    }
}
