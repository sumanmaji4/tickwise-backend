package com.tickwise.ticket.intake;

import com.tickwise.shared.domain.Priority;
import com.tickwise.ticket.NewTicket;
import com.tickwise.ticket.TicketService;
import com.tickwise.ticket.TicketView;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The single entry point for customer messages from every channel. */
@Service
public class IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    static final int MAX_SUBJECT = 300;
    static final Priority DEFAULT_PRIORITY = Priority.MEDIUM;

    private final TicketService tickets;

    IntakeService(TicketService tickets) {
        this.tickets = tickets;
    }

    public enum Outcome { CREATED, APPENDED, DUPLICATE }

    public record Result(Outcome outcome, Long ticketId, ThreadResolver.Method threadedBy) {}

    /**
     * Idempotent per Message-ID: a redelivered email is reported as DUPLICATE. Two concurrent
     * deliveries of the same email are stopped by the unique index on (tenant, Message-ID); the
     * loser's transaction fails and its caller retries, then sees DUPLICATE.
     */
    @Transactional
    public Result receive(InboundMessage message) {
        if (message.emailMessageId() != null && tickets.emailAlreadyReceived(message.tenantId(), message.emailMessageId())) {
            return new Result(Outcome.DUPLICATE, null, null);
        }
        Optional<ThreadResolver.Match> match = new ThreadResolver(lookup(message)).resolve(message);
        if (match.isPresent()) {
            long ticketId = match.get().ticketId();
            tickets.addCustomerMessage(message.tenantId(), ticketId, message.body(), message.emailMessageId(),
                    message.attachments());
            log.info("Inbound {} message appended to ticket {} (threaded by {})", message.channel(), ticketId,
                    match.get().method());
            return new Result(Outcome.APPENDED, ticketId, match.get().method());
        }
        TicketView ticket = tickets.create(message.tenantId(), new NewTicket(message.channel(), message.fromEmail(),
                message.fromName(), subjectOf(message.subject()), message.body(), DEFAULT_PRIORITY,
                message.emailMessageId(), message.attachments()));
        log.info("Inbound {} message created ticket {}", message.channel(), ticket.id());
        return new Result(Outcome.CREATED, ticket.id(), null);
    }

    private ThreadResolver.Lookup lookup(InboundMessage message) {
        return new ThreadResolver.Lookup() {
            @Override
            public Optional<ThreadResolver.Candidate> ticket(long ticketId) {
                return tickets.find(message.tenantId(), ticketId)
                        .map(t -> new ThreadResolver.Candidate(t.id(), t.status(), t.customer().email()));
            }

            @Override
            public Optional<Long> ticketForMessageIds(List<String> messageIds) {
                return tickets.ticketIdForEmailMessageIds(message.tenantId(), messageIds);
            }
        };
    }

    static String subjectOf(String subject) {
        String s = subject == null ? "" : subject.strip();
        if (s.isEmpty()) {
            return "(no subject)";
        }
        return s.length() > MAX_SUBJECT ? s.substring(0, MAX_SUBJECT) : s;
    }
}
