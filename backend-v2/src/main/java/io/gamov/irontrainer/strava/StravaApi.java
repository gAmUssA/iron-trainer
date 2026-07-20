package io.gamov.irontrainer.strava;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Typed Strava API client (Quarkus REST Client) — replaces the hand-rolled
 * httpx calls in app/strava.py. Base URL is https://www.strava.com
 * (quarkus.rest-client.strava.url); the OAuth token endpoint and the v3 API sit
 * under the same host. Only the calls the sync needs are declared; the OAuth
 * authorize/exchange flow lives in the OAuth vertical (bean xtre). */
@RegisterRestClient(configKey = "strava")
public interface StravaApi {

    /** POST /oauth/token (form-encoded) — refresh an access token. Returns the
     * token JSON: {access_token, refresh_token, expires_at, athlete?}. */
    @POST
    @Path("/oauth/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    Map<String, Object> token(@FormParam("client_id") String clientId,
                              @FormParam("client_secret") String clientSecret,
                              @FormParam("grant_type") String grantType,
                              @FormParam("refresh_token") String refreshToken);

    /** POST /oauth/token — exchange an authorization code for tokens (login).
     * Same endpoint as refresh, but the form carries `code` + grant_type
     * authorization_code. Port of strava.exchange_code. The response includes
     * the `athlete` block used to find-or-create the user. */
    @POST
    @Path("/oauth/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    Map<String, Object> exchangeCode(@FormParam("client_id") String clientId,
                                     @FormParam("client_secret") String clientSecret,
                                     @FormParam("code") String code,
                                     @FormParam("grant_type") String grantType);

    /** POST /oauth/deauthorize — revoke the app's access for this athlete at
     * Strava (disconnect). Port of strava.deauthorize. */
    @POST
    @Path("/oauth/deauthorize")
    void deauthorize(@HeaderParam("Authorization") String authorization);

    /** GET /api/v3/athlete/activities — one page of activity summaries. `after`
     * (unix seconds) is omitted when null (Quarkus drops null query params). */
    @GET
    @Path("/api/v3/athlete/activities")
    List<Map<String, Object>> activities(@HeaderParam("Authorization") String authorization,
                                         @QueryParam("per_page") int perPage,
                                         @QueryParam("page") int page,
                                         @QueryParam("after") Long after);

    /** GET /api/v3/activities/{id} — detailed activity (includes device_name). */
    @GET
    @Path("/api/v3/activities/{id}")
    Map<String, Object> activityDetail(@HeaderParam("Authorization") String authorization,
                                       @PathParam("id") long id);
}
