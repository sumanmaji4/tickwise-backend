package com.tickwise.ticket;

import com.tickwise.shared.domain.Priority;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain events published by the ticket module inside the transaction that caused them.
 * Listeners that must be atomic with the change (audit, outbox, read models) use
 * {@code @EventListener}; side effects outside the database (real-time push, auto-assignment in
 * its own transaction) use {@code @TransactionalEventListener(AFTER_COMMIT)}.
 */
public final class TicketEvents {

    private TicketEvents() {}

    /** Marker for anything that changes what an agent dashboard shows. */
    public sealed interface TicketChange permits Created, StatusChanged, PriorityChanged, Assigned,
            ReplySent, MessageReceived, SlaFlagged, Deleted {
        UUID tenantId();

        long ticketId();
    }

    public record Created(UUID tenantId, long ticketId, Channel channel, Priority priority, UUID teamId,
                          String customerEmail, Instant createdAt) implements TicketChange {}

    public record StatusChanged(UUID tenantId, long ticketId, TicketStatus from, TicketStatus to,
                                UUID actorUserId, Instant at) implements TicketChange {}

    public record PriorityChanged(UUID tenantId, long ticketId, Priority from, Priority to,
                                  UUID actorUserId, Instant at) implements TicketChange {}

    public record Assigned(UUID tenantId, long ticketId, UUID assigneeId, UUID previousAssigneeId, UUID teamId,
                           UUID actorUserId, Instant at) implements TicketChange {}

    /** An agent replied to the customer (OUTBOUND). */
    public record ReplySent(UUID tenantId, long ticketId, UUID messageId, UUID authorUserId, Instant at)
            implements TicketChange {}

    /** The customer wrote on an existing ticket (INBOUND). */
    public record MessageReceived(UUID tenantId, long ticketId, UUID messageId, Instant at) implements TicketChange {}

    /** An admin soft-deleted the ticket; it disappears from queues, search and reports. */
    public record Deleted(UUID tenantId, long ticketId, UUID actorUserId, Instant at) implements TicketChange {}

    /** An SLA warning or breach was recorded on the ticket. */
    public record SlaFlagged(UUID tenantId, long ticketId, boolean breached) implements TicketChange {}
}
