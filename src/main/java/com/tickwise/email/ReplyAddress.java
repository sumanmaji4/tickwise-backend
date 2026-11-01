package com.tickwise.email;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inbound addresses: {@code <tenantSlug>@<inbound-domain>} opens tickets, and
 * {@code <tenantSlug>+t<ticketId>@<inbound-domain>} (the Reply-To of every email we send)
 * continues one. The tenant always comes from the address, never from message content.
 */
final class ReplyAddress {

    private static final Pattern LOCAL_PART = Pattern.compile("([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\\+t(\\d{1,18}))?");

    record Parsed(String tenantSlug, Long ticketId) {}

    private ReplyAddress() {}

    static String forTicket(String tenantSlug, long ticketId, String inboundDomain) {
        return tenantSlug + "+t" + ticketId + "@" + inboundDomain;
    }

    static String forTenant(String tenantSlug, String inboundDomain) {
        return tenantSlug + "@" + inboundDomain;
    }

    static Optional<Parsed> parse(String address, String inboundDomain) {
        if (address == null) {
            return Optional.empty();
        }
        String normalized = address.strip().toLowerCase(Locale.ROOT);
        int at = normalized.lastIndexOf('@');
        if (at <= 0 || !normalized.substring(at + 1).equals(inboundDomain.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        Matcher m = LOCAL_PART.matcher(normalized.substring(0, at));
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(m.group(1), m.group(2) == null ? null : Long.parseLong(m.group(2))));
    }
}
