package com.sirilerklab.svcgeyser.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class XboxAuthVerifier {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final String PROFILE_URL =
            "https://profile.xboxlive.com/users/me/profile/settings?settings=Gamertag";

    /**
     * Verifies an XBL3.0 authorization header against Xbox Live and returns the confirmed XUID,
     * or null if verification fails. Blocks for up to ~5 s.
     */
    public static String verify(String xblHeader) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(PROFILE_URL))
                    .header("Authorization", xblHeader)
                    .header("x-xbl-contract-version", "2")
                    .header("Accept", "application/json")
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;

            // Extract id from {"profileUsers":[{"id":"<XUID>",...}]}
            String body = resp.body();
            int i = body.indexOf("\"id\":\"");
            if (i < 0) return null;
            i += 6;
            int j = body.indexOf('"', i);
            return j > i ? body.substring(i, j) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
