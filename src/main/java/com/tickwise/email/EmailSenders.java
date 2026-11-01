package com.tickwise.email;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Chooses the transport from {@code tickwise.email.provider}. All transports use HTTP APIs
 * (Mailpit's send API locally, Brevo's transactional API in production), so no SMTP library is
 * needed.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmailProperties.class)
class EmailSenders {

    private static final Logger log = LoggerFactory.getLogger(EmailSenders.class);

    @Bean
    EmailSender emailSender(EmailProperties properties, RestClient.Builder builder) {
        String provider = properties.provider() == null ? "log" : properties.provider().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "mailpit" -> mailpit(builder.baseUrl(properties.mailpitUrl().toString()).build());
            case "brevo" -> brevo(builder.baseUrl(properties.brevoApiUrl().toString())
                    .defaultHeader("api-key", properties.brevoApiKey()).build());
            case "log" -> email -> log.info("Email not sent (provider=log): {}", email.subject());
            default -> throw new IllegalStateException("Unknown tickwise.email.provider: " + properties.provider());
        };
    }

    /** Mailpit's {@code POST /api/v1/send}. */
    static EmailSender mailpit(RestClient http) {
        return email -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("From", Map.of("Email", email.fromAddress(), "Name", email.fromName()));
            body.put("To", List.of(address(email.toAddress(), email.toName(), "Email", "Name")));
            body.put("ReplyTo", List.of(Map.of("Email", email.replyTo())));
            body.put("Subject", email.subject());
            body.put("Text", email.text());
            body.put("Headers", email.headers());
            http.post().uri("/api/v1/send").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
        };
    }

    /** Brevo's {@code POST /v3/smtp/email}; custom threading headers are passed through {@code headers}. */
    static EmailSender brevo(RestClient http) {
        return email -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sender", Map.of("email", email.fromAddress(), "name", email.fromName()));
            body.put("to", List.of(address(email.toAddress(), email.toName(), "email", "name")));
            body.put("replyTo", Map.of("email", email.replyTo()));
            body.put("subject", email.subject());
            body.put("textContent", email.text());
            body.put("headers", email.headers());
            http.post().uri("/v3/smtp/email").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
        };
    }

    private static Map<String, String> address(String email, String name, String emailKey, String nameKey) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(emailKey, email);
        if (name != null && !name.isBlank()) {
            m.put(nameKey, name);
        }
        return m;
    }
}
