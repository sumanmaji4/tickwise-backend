package com.tickwise.email;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Recognises machine-generated mail so that an out-of-office reply to our acknowledgement does
 * not open a ticket, which would trigger another acknowledgement: a mail loop. Checks follow
 * RFC 3834 ({@code Auto-Submitted}) and common de-facto headers, then fall back to sender and
 * subject heuristics.
 */
final class AutoReplyDetector {

    private static final List<String> AUTOMATED_SENDERS = List.of(
            "mailer-daemon@", "postmaster@", "noreply@", "no-reply@", "donotreply@", "do-not-reply@");

    private static final Pattern AUTO_SUBJECT = Pattern.compile(
            "^(auto(matic)?[ -]?reply|auto:|out of (the )?office|ooo\\b|away from (the )?office|"
                    + "abwesenheitsnotiz|r[ée]ponse automatique|absence|delivery status notification|"
                    + "undeliverable|mail delivery failed|returned mail)",
            Pattern.CASE_INSENSITIVE);

    private AutoReplyDetector() {}

    /** The reason the message is automated, or empty for a human-written message. Header names are case-insensitive. */
    static Optional<String> detect(Map<String, String> headers, String fromAddress, String subject) {
        String autoSubmitted = header(headers, "Auto-Submitted");
        if (autoSubmitted != null && !autoSubmitted.strip().equalsIgnoreCase("no")) {
            return Optional.of("Auto-Submitted: " + autoSubmitted.strip());
        }
        // X-Auto-Response-Suppress is deliberately not checked: Outlook sets it on ordinary mail too.
        for (String h : List.of("X-Autoreply", "X-Autorespond")) {
            if (header(headers, h) != null) {
                return Optional.of(h + " header");
            }
        }
        String precedence = header(headers, "Precedence");
        if (precedence != null && List.of("bulk", "junk", "list", "auto_reply").contains(precedence.strip().toLowerCase(Locale.ROOT))) {
            return Optional.of("Precedence: " + precedence.strip());
        }
        if (header(headers, "List-Id") != null || header(headers, "List-Unsubscribe") != null) {
            return Optional.of("mailing list");
        }
        if (fromAddress != null) {
            String from = fromAddress.toLowerCase(Locale.ROOT);
            for (String prefix : AUTOMATED_SENDERS) {
                if (from.startsWith(prefix)) {
                    return Optional.of("automated sender " + fromAddress);
                }
            }
        }
        if (subject != null && AUTO_SUBJECT.matcher(subject.strip()).find()) {
            return Optional.of("auto-reply subject");
        }
        return Optional.empty();
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }
}
