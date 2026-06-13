package com.sirilerklab.svcgeyser.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class SessionJwt {
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private static final String HEADER = ENC.encodeToString(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    /** Issues an HS256 JWT valid for 1 hour containing the XUID claim. */
    public static String issue(String xuid, String secret) {
        long exp = System.currentTimeMillis() / 1000 + 3600;
        String payload = ENC.encodeToString(
                ("{\"xuid\":\"" + xuid + "\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        String signing = HEADER + "." + payload;
        return signing + "." + ENC.encodeToString(hmac(signing, secret));
    }

    /** Verifies the token and returns the XUID, or null if invalid or expired. */
    public static String extractXuid(String token, String secret) {
        String[] p = token.split("\\.", 3);
        if (p.length != 3) return null;
        String expected = ENC.encodeToString(hmac(p[0] + "." + p[1], secret));
        if (!expected.equals(p[2])) return null;
        String json = new String(DEC.decode(p[1]), StandardCharsets.UTF_8);
        String xuid = jsonStr(json, "xuid");
        String expStr = jsonNum(json, "exp");
        if (xuid == null || expStr == null) return null;
        return Long.parseLong(expStr) > System.currentTimeMillis() / 1000 ? xuid : null;
    }

    private static byte[] hmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String jsonStr(String json, String key) {
        String tag = "\"" + key + "\":\"";
        int i = json.indexOf(tag);
        if (i < 0) return null;
        i += tag.length();
        int j = json.indexOf('"', i);
        return j > i ? json.substring(i, j) : null;
    }

    private static String jsonNum(String json, String key) {
        String tag = "\"" + key + "\":";
        int i = json.indexOf(tag);
        if (i < 0) return null;
        i += tag.length();
        int j = i;
        while (j < json.length() && (Character.isDigit(json.charAt(j)) || json.charAt(j) == '-')) j++;
        return j > i ? json.substring(i, j) : null;
    }
}
