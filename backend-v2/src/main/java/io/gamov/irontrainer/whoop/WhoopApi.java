package io.gamov.irontrainer.whoop;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Typed WHOOP v2 API client (bean 4a6s). Base URL https://api.prod.whoop.com
 * (quarkus.rest-client.whoop.url) — the OAuth endpoints and the v2 collections
 * share the host, as with Strava.
 *
 * <p>v2 only. v1 is unsupported (its webhooks were removed 2025-11-01) and no v1
 * REST sunset date has been published, so there is nothing to fall back to.
 *
 * <p><b>Data endpoints live under /developer/v2/…, OAuth does not.</b> The docs
 * present the endpoint as "get/v2/cycle" while the real URL is
 * https://api.prod.whoop.com/developer/v2/cycle — an easy prefix to miss, and the
 * failure is asymmetric and confusing: the token exchange succeeds (it really is
 * at /oauth/oauth2/token, no prefix), so the connection looks healthy and only the
 * first data call 404s. Confirmed against a live account 2026-08-21.
 *
 * <p>Collection semantics that shape every call here:
 * <ul>
 *   <li>{@code limit} maxes out at <b>25</b> — not a suggestion, the API rejects more.</li>
 *   <li>{@code start} is inclusive, {@code end} exclusive and defaults to now.</li>
 *   <li>Results are sorted <b>descending</b> by start time.</li>
 *   <li>Paging is by opaque {@code nextToken}, passed back as {@code nextToken}.</li>
 *   <li>There is <b>no {@code updated_since} filter</b> anywhere in v2 — start/end
 *       filter on event time, not modification time. That absence is why the
 *       incremental sync re-walks a few days rather than asking for changes.</li>
 * </ul>
 */
@RegisterRestClient(configKey = "whoop")
public interface WhoopApi {

    /** POST /oauth/oauth2/token — exchange an authorization code for tokens.
     * Form-encoded, like Strava's. */
    @POST
    @Path("/oauth/oauth2/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    Map<String, Object> exchangeCode(@FormParam("client_id") String clientId,
                                     @FormParam("client_secret") String clientSecret,
                                     @FormParam("code") String code,
                                     @FormParam("grant_type") String grantType,
                                     @FormParam("redirect_uri") String redirectUri);

    /** POST /oauth/oauth2/token — refresh.
     *
     * <p>WHOOP refresh tokens are <b>single-use and rotate</b>: every successful
     * refresh invalidates the pair that produced it, and WHOOP's docs warn that
     * concurrent refreshes fail. The response's new refresh_token MUST be persisted
     * or the athlete is locked out and has to reconnect by hand — see
     * {@link WhoopTokens} for the serialization that makes that safe. */
    @POST
    @Path("/oauth/oauth2/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    Map<String, Object> refresh(@FormParam("client_id") String clientId,
                                @FormParam("client_secret") String clientSecret,
                                @FormParam("grant_type") String grantType,
                                @FormParam("refresh_token") String refreshToken,
                                @FormParam("scope") String scope);

    /** GET /v2/cycle — physiological cycles (day strain, kilojoules, heart rate). */
    @GET
    @Path("/developer/v2/cycle")
    Map<String, Object> cycles(@HeaderParam("Authorization") String authorization,
                               @QueryParam("start") String start,
                               @QueryParam("end") String end,
                               @QueryParam("limit") int limit,
                               @QueryParam("nextToken") String nextToken);

    /** GET /v2/recovery — recovery score, HRV, RHR, SpO2, skin temperature.
     * Each row carries cycle_id and sleep_id, which is how it joins the rest. */
    @GET
    @Path("/developer/v2/recovery")
    Map<String, Object> recovery(@HeaderParam("Authorization") String authorization,
                                 @QueryParam("start") String start,
                                 @QueryParam("end") String end,
                                 @QueryParam("limit") int limit,
                                 @QueryParam("nextToken") String nextToken);

    /** GET /v2/activity/sleep — sleep records with stage summary and performance.
     *
     * <p>Fetched as a COLLECTION and indexed by cycle_id, deliberately. The
     * per-cycle route /v2/cycle/{id}/sleep returns the same data one cycle at a
     * time — ~1,800 extra requests on a five-year backfill for nothing. */
    @GET
    @Path("/developer/v2/activity/sleep")
    Map<String, Object> sleep(@HeaderParam("Authorization") String authorization,
                              @QueryParam("start") String start,
                              @QueryParam("end") String end,
                              @QueryParam("limit") int limit,
                              @QueryParam("nextToken") String nextToken);

    /** GET /v2/user/profile/basic — identifies which WHOOP member a token belongs
     * to. Used once at connect time to stamp whoop_user_id, so a later reconnect
     * to a DIFFERENT member is detectable rather than silently blending two
     * people's data into one athlete. */
    @GET
    @Path("/developer/v2/user/profile/basic")
    Map<String, Object> profile(@HeaderParam("Authorization") String authorization);

    /** A collection response is {records: [...], next_token: "..."} — this pulls
     * the records out with the unchecked cast localized to one place. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> records(Map<String, Object> page) {
        Object recs = page == null ? null : page.get("records");
        return recs instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    /** The paging cursor, or null at the end of the collection. WHOOP returns
     * next_token; treat an empty string as exhausted too. */
    static String nextToken(Map<String, Object> page) {
        Object t = page == null ? null : page.get("next_token");
        String s = t instanceof String str ? str : null;
        return s == null || s.isBlank() ? null : s;
    }
}
