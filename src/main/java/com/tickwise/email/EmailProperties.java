package com.tickwise.email;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * {@code tickwise.email.*}. {@code provider} chooses the outbound transport: {@code log} (tests),
 * {@code mailpit} (local, HTTP send API) or {@code brevo} (production).
 */
@ConfigurationProperties("tickwise.email")
record EmailProperties(
        String provider,
        String inboundDomain,
        String fromAddress,
        String fromName,
        URI mailpitUrl,
        URI brevoApiUrl,
        String brevoApiKey,
        Webhook webhook,
        DataSize maxAttachmentSize,
        DataSize maxWebhookPayload) {

    /**
     * {@code mode=hmac}: header carries {@code sha256=<hex HMAC-SHA256(secret, raw body)>}.
     * {@code mode=token}: header carries the shared secret itself (for providers that can only
     * add a static header). An empty secret rejects every request.
     */
    record Webhook(String mode, String header, String secret) {

        @Override
        public String toString() {
            return "Webhook[mode=" + mode + ", header=" + header + "]";
        }
    }

    String fromDomain() {
        return fromAddress.substring(fromAddress.indexOf('@') + 1);
    }

    @Override
    public String toString() {
        return "EmailProperties[provider=" + provider + ", inboundDomain=" + inboundDomain + ", from=" + fromAddress + "]";
    }
}
