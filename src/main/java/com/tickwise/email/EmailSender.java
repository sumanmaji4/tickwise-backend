package com.tickwise.email;

import java.util.Map;

/** Outbound transport. Implementations throw on failure; the outbox retries with backoff. */
interface EmailSender {

    void send(OutgoingEmail email);

    record OutgoingEmail(String fromAddress, String fromName, String toAddress, String toName, String replyTo,
                         String subject, String text, Map<String, String> headers) {}
}
