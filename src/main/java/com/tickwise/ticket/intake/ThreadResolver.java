package com.tickwise.ticket.intake;

import com.tickwise.ticket.TicketStatus;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides which existing ticket, if any, an inbound message continues. Checks run in order of
 * reliability:
 *
 * <ol>
 *   <li><b>Reply token</b> in the recipient ({@code acme+t123@...}), set as Reply-To on every email we send.</li>
 *   <li><b>In-Reply-To / References</b> matching a Message-ID we stored for that ticket.</li>
 *   <li><b>{@code [#123]}</b> in the subject.</li>
 * </ol>
 *
 * The token and the subject tag are guessable, so they only thread when the sender is the
 * ticket's customer; otherwise anyone could inject messages into another customer's ticket.
 * Message-IDs are effectively unguessable, so a header match is accepted from any sender (for
 * example a colleague the customer copied in). A match on a CLOSED ticket is ignored, so the
 * message starts a new ticket. Pure logic over a {@link Lookup}, so it can be unit tested.
 */
public final class ThreadResolver {

    static final Pattern SUBJECT_REFERENCE = Pattern.compile("\\[#(\\d{1,18})]");

    public enum Method { REPLY_TOKEN, HEADERS, SUBJECT }

    public record Candidate(long ticketId, TicketStatus status, String customerEmail) {}

    public record Match(long ticketId, Method method) {}

    /** Tenant-scoped lookups; implemented over TicketService. */
    public interface Lookup {
        Optional<Candidate> ticket(long ticketId);

        Optional<Long> ticketForMessageIds(List<String> messageIds);
    }

    private final Lookup lookup;

    public ThreadResolver(Lookup lookup) {
        this.lookup = lookup;
    }

    public Optional<Match> resolve(InboundMessage message) {
        String sender = normalize(message.fromEmail());
        if (message.replyTokenTicketId() != null) {
            Optional<Match> byToken = lookup.ticket(message.replyTokenTicketId())
                    .filter(c -> open(c) && sender.equals(normalize(c.customerEmail())))
                    .map(c -> new Match(c.ticketId(), Method.REPLY_TOKEN));
            if (byToken.isPresent()) {
                return byToken;
            }
        }
        if (!message.threadMessageIds().isEmpty()) {
            Optional<Match> byHeaders = lookup.ticketForMessageIds(message.threadMessageIds())
                    .flatMap(lookup::ticket)
                    .filter(ThreadResolver::open)
                    .map(c -> new Match(c.ticketId(), Method.HEADERS));
            if (byHeaders.isPresent()) {
                return byHeaders;
            }
        }
        return subjectReference(message.subject())
                .flatMap(lookup::ticket)
                .filter(c -> open(c) && sender.equals(normalize(c.customerEmail())))
                .map(c -> new Match(c.ticketId(), Method.SUBJECT));
    }

    static Optional<Long> subjectReference(String subject) {
        if (subject == null) {
            return Optional.empty();
        }
        Matcher m = SUBJECT_REFERENCE.matcher(subject);
        return m.find() ? Optional.of(Long.parseLong(m.group(1))) : Optional.empty();
    }

    private static boolean open(Candidate c) {
        return c.status() != TicketStatus.CLOSED;
    }

    private static String normalize(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
