package io.gamov.irontrainer.strava;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/** Stands up a real WireMock HTTP server standing in for strava.com and points
 * the Strava REST client at it (quarkus.rest-client.strava.url). Stubs the OAuth
 * token refresh + one page of activity summaries (a Bike + a Run). */
public class WireMockStrava implements QuarkusTestResourceLifecycleManager {

    private WireMockServer wm;

    @Override
    public Map<String, String> start() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();

        // refresh_token grant: real Strava returns ONLY the token fields — the
        // athlete object is included on the initial authorization_code exchange,
        // never on a refresh. Keep the stub faithful so refresh can't be tested
        // as if it populated athlete identity.
        wm.stubFor(post(urlPathEqualTo("/oauth/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"NEW_TOKEN\",\"refresh_token\":\"REFRESH2\","
                        + "\"expires_at\":9999999999}")));

        wm.stubFor(get(urlPathEqualTo("/api/v3/athlete/activities")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("[{\"id\":111,\"type\":\"Ride\",\"sport_type\":\"Ride\","
                        + "\"start_date_local\":\"2026-07-01T08:00:00\",\"name\":\"Ride A\","
                        + "\"moving_time\":3600,\"distance\":30000,\"average_watts\":200,"
                        + "\"weighted_average_watts\":210,\"average_heartrate\":145,\"device_watts\":true},"
                        + "{\"id\":222,\"type\":\"Run\",\"sport_type\":\"Run\","
                        + "\"start_date_local\":\"2026-07-02T07:00:00\",\"name\":\"Run B\","
                        + "\"moving_time\":1800,\"distance\":6000,\"average_heartrate\":160}]")));

        // Activity detail (device_name enrichment for de-dup). 501/502 carry device
        // names; 555 → 429 (rate limited → breaks the loop); 556 → 500 (skipped).
        wm.stubFor(get(urlPathEqualTo("/api/v3/activities/501")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":501,\"device_name\":\"Garmin Edge 530\"}")));
        wm.stubFor(get(urlPathEqualTo("/api/v3/activities/502")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":502,\"device_name\":\"Apple Watch\"}")));
        wm.stubFor(get(urlPathEqualTo("/api/v3/activities/555"))
                .willReturn(aResponse().withStatus(429)));
        wm.stubFor(get(urlPathEqualTo("/api/v3/activities/556"))
                .willReturn(aResponse().withStatus(500)));

        return Map.of("quarkus.rest-client.strava.url", wm.baseUrl());
    }

    @Override
    public void stop() {
        if (wm != null) wm.stop();
    }
}
