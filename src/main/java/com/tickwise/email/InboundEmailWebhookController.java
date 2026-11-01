package com.tickwise.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickwise.shared.domain.Times;
import com.tickwise.shared.error.ApiException;
import com.tickwise.shared.error.BadRequestException;
import com.tickwise.shared.error.ProblemTypes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives the email provider's parsed-email webhook. It verifies the signature, stores each
 * raw item and returns 200 at once. Parsing, threading and attachment download happen in the
 * worker, so a slow step never makes the provider time out and retry.
 */
@RestController
@Tag(name = "Webhooks")
@SecurityRequirements
class InboundEmailWebhookController {

    private static final Logger log = LoggerFactory.getLogger(InboundEmailWebhookController.class);

    private final InboundEmailRepository inbox;
    private final EmailProperties properties;
    private final WebhookVerifier verifier;
    private final ObjectMapper json;
    private final Clock clock;

    InboundEmailWebhookController(InboundEmailRepository inbox, EmailProperties properties, ObjectMapper json, Clock clock) {
        this.inbox = inbox;
        this.properties = properties;
        this.verifier = new WebhookVerifier(properties.webhook());
        this.json = json;
        this.clock = clock;
    }

    static class WebhookRejectedException extends ApiException {
        WebhookRejectedException() {
            super(HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHORIZED, "Unauthorized", "Webhook signature is invalid");
        }
    }

    static class PayloadTooLargeException extends ApiException {
        PayloadTooLargeException() {
            super(HttpStatus.PAYLOAD_TOO_LARGE, URI.create("urn:tickwise:problem:payload-too-large"),
                    "Payload too large", "Webhook payload exceeds the configured limit");
        }
    }

    @PostMapping("/webhooks/inbound-email")
    @Transactional
    @Operation(summary = "Inbound email from the provider (signed); queued for asynchronous processing")
    Map<String, Integer> receive(HttpServletRequest request) throws IOException {
        long max = properties.maxWebhookPayload().toBytes();
        byte[] body = request.getInputStream().readNBytes((int) max + 1);
        if (body.length > max) {
            throw new PayloadTooLargeException();
        }
        if (!verifier.verify(body, request.getHeader(properties.webhook().header()))) {
            log.warn("Rejected inbound-email webhook with an invalid signature from {}", request.getRemoteAddr());
            throw new WebhookRejectedException();
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (IOException e) {
            throw new BadRequestException("Webhook body is not valid JSON");
        }
        List<JsonNode> items = new ArrayList<>();
        if (root != null && root.has("items") && root.get("items").isArray()) {
            root.get("items").forEach(items::add);
        } else if (root != null && root.isObject()) {
            items.add(root);
        }
        var now = Times.now(clock);
        for (JsonNode item : items) {
            inbox.save(new InboundEmail(item.toString(), now));
        }
        log.info("Queued {} inbound email(s)", items.size());
        return Map.of("accepted", items.size());
    }
}
