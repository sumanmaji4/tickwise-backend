package com.tickwise.email;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Downloads attachment bytes from Brevo; inbound webhooks only carry a download token. */
@Component
class AttachmentFetcher {

    private final EmailProperties properties;
    private final RestClient http;

    AttachmentFetcher(EmailProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.http = builder.build();
    }

    boolean supported() {
        return "brevo".equalsIgnoreCase(properties.provider()) && properties.brevoApiKey() != null
                && !properties.brevoApiKey().isBlank();
    }

    byte[] fetch(String downloadToken) {
        return http.get()
                .uri(properties.brevoApiUrl().resolve("/v3/inbound/attachments/" + downloadToken))
                .header("api-key", properties.brevoApiKey())
                .header(HttpHeaders.ACCEPT, "application/octet-stream")
                .retrieve()
                .body(byte[].class);
    }
}
