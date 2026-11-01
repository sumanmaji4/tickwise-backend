package com.tickwise.email;

import java.util.List;
import java.util.Map;

/** Provider-neutral view of one inbound email, before tenant resolution. */
record ParsedEmail(
        String messageId,
        List<String> threadMessageIds,
        String fromAddress,
        String fromName,
        List<String> recipients,
        String subject,
        String body,
        Map<String, String> headers,
        List<Attachment> attachments) {

    /** Either {@code base64Content} (inline) or {@code downloadToken} (fetched from the provider) is set. */
    record Attachment(String filename, String contentType, long size, String downloadToken, String base64Content) {}
}
