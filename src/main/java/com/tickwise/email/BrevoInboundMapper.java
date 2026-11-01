package com.tickwise.email;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Maps one item of Brevo's inbound-parsing webhook ({@code {"items":[...]}}) to a
 * {@link ParsedEmail}. Tolerant of missing fields; header values may be strings or arrays.
 * The body prefers {@code ExtractedMarkdownMessage} (the reply without the quoted history),
 * then the text part, then the HTML part converted to text.
 */
final class BrevoInboundMapper {

    static final int MAX_BODY_CHARS = 100_000;

    private BrevoInboundMapper() {}

    static ParsedEmail map(JsonNode item) {
        Map<String, String> headers = headers(item.path("Headers"));
        String messageId = normalizeMessageId(text(item, "MessageId"));
        if (messageId == null) {
            messageId = normalizeMessageId(headers.get("Message-ID"));
        }
        LinkedHashSet<String> thread = new LinkedHashSet<>();
        addIds(thread, text(item, "InReplyTo"));
        addIds(thread, headerValue(headers, "In-Reply-To"));
        addIds(thread, headerValue(headers, "References"));

        List<String> recipients = new ArrayList<>();
        for (String field : List.of("To", "Cc")) {
            for (JsonNode r : item.path(field)) {
                String address = text(r, "Address");
                if (address != null) {
                    recipients.add(address);
                }
            }
        }

        String body = text(item, "ExtractedMarkdownMessage");
        if (body == null || body.isBlank()) {
            body = text(item, "RawTextBody");
        }
        if (body == null || body.isBlank()) {
            body = HtmlToText.convert(text(item, "RawHtmlBody"));
        }
        body = body == null ? "" : body.strip();
        if (body.length() > MAX_BODY_CHARS) {
            body = body.substring(0, MAX_BODY_CHARS) + "\n[message truncated]";
        }

        List<ParsedEmail.Attachment> attachments = new ArrayList<>();
        for (JsonNode a : item.path("Attachments")) {
            attachments.add(new ParsedEmail.Attachment(
                    text(a, "Name"), text(a, "ContentType"), a.path("ContentLength").asLong(0),
                    text(a, "DownloadToken"), text(a, "Content")));
        }

        JsonNode from = item.path("From");
        return new ParsedEmail(messageId, List.copyOf(thread), text(from, "Address"), text(from, "Name"),
                recipients, text(item, "Subject"), body, headers, attachments);
    }

    /** Message-IDs are compared verbatim, so always store them in their {@code <id@host>} form. */
    static String normalizeMessageId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String id = raw.strip();
        return id.startsWith("<") ? id : "<" + id + ">";
    }

    private static void addIds(LinkedHashSet<String> target, String headerValue) {
        if (headerValue == null) {
            return;
        }
        for (String token : headerValue.trim().split("[\\s,]+")) {
            String id = normalizeMessageId(token);
            if (id != null) {
                target.add(id);
            }
        }
    }

    private static Map<String, String> headers(JsonNode node) {
        Map<String, String> headers = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            headers.put(e.getKey(), v.isArray() && !v.isEmpty() ? v.get(0).asText() : v.asText());
        });
        return headers;
    }

    private static String headerValue(Map<String, String> headers, String name) {
        return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
