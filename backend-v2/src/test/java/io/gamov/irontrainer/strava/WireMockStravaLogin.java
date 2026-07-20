package io.gamov.irontrainer.strava;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/** WireMock strava.com for the OAuth callback (login) path: POST /oauth/token
 * returns a token WITH the `athlete` block — as real Strava does on the initial
 * authorization_code exchange (unlike a refresh). The athlete id 424242 is the
 * one the auth-mode allowlist test permits. */
public class WireMockStravaLogin implements QuarkusTestResourceLifecycleManager {

    private WireMockServer wm;

    @Override
    public Map<String, String> start() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        wm.stubFor(post(urlPathEqualTo("/oauth/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"LOGIN_TOKEN\",\"refresh_token\":\"LOGIN_REFRESH\","
                        + "\"expires_at\":9999999999,"
                        + "\"athlete\":{\"id\":424242,\"firstname\":\"Cal\",\"lastname\":\"Back\"}}")));
        return Map.of("quarkus.rest-client.strava.url", wm.baseUrl());
    }

    @Override
    public void stop() {
        if (wm != null) wm.stop();
    }
}
