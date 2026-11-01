package com.tickwise.email;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authenticates inbound webhook calls. Comparisons are constant-time; a missing secret fails closed. */
final class WebhookVerifier {

    static final String HMAC_PREFIX = "sha256=";

    private final String mode;
    private final byte[] secret;

    WebhookVerifier(EmailProperties.Webhook config) {
        this.mode = config.mode() == null ? "hmac" : config.mode().toLowerCase(Locale.ROOT);
        this.secret = config.secret() == null ? new byte[0] : config.secret().getBytes(StandardCharsets.UTF_8);
    }

    boolean verify(byte[] body, String headerValue) {
        if (secret.length == 0 || headerValue == null || headerValue.isBlank()) {
            return false;
        }
        byte[] presented;
        byte[] expected;
        if (mode.equals("token")) {
            presented = headerValue.strip().getBytes(StandardCharsets.UTF_8);
            expected = secret;
        } else {
            String value = headerValue.strip();
            if (value.regionMatches(true, 0, HMAC_PREFIX, 0, HMAC_PREFIX.length())) {
                value = value.substring(HMAC_PREFIX.length());
            }
            try {
                presented = HexFormat.of().parseHex(value.toLowerCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return false;
            }
            expected = hmac(secret, body);
        }
        return MessageDigest.isEqual(presented, expected);
    }

    static byte[] hmac(byte[] key, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code sha256=<hex>} for a body, as a sender would compute it (used in tests and load tests). */
    static String sign(String secret, byte[] body) {
        return HMAC_PREFIX + HexFormat.of().formatHex(hmac(secret.getBytes(StandardCharsets.UTF_8), body));
    }
}
