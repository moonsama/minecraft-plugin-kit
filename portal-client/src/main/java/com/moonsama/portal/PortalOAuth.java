package com.moonsama.portal;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public final class PortalOAuth {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private PortalOAuth() {
    }

    public record Attempt(String state, String verifier, String challenge) {
    }

    public static Attempt newAttempt() {
        String state = randomBase64Url(24);
        String verifier = randomBase64Url(48);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return new Attempt(state, verifier, BASE64_URL.encodeToString(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static URI authorizationUri(
            URI portalUrl,
            String clientId,
            URI redirectUri,
            String state,
            String challenge
    ) {
        String base = portalUrl.toString().replaceAll("/+$", "");
        return URI.create(base + "/oauth"
                + "?client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirectUri.toString())
                + "&response_type=code"
                + "&scope=" + encode("openid profile economy")
                + "&state=" + encode(state)
                + "&code_challenge=" + encode(challenge)
                + "&code_challenge_method=S256");
    }

    private static String randomBase64Url(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return BASE64_URL.encodeToString(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
