package com.tickwise.assignment;

import com.tickwise.ticket.TicketEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Auto-assigns after the ticket is committed, in its own transaction. A failure (for example a
 * concurrent claim winning the race) never rolls back ticket creation; the ticket simply stays
 * in the queue.
 */
@Component
class AutoAssignListener {

    private static final Logger log = LoggerFactory.getLogger(AutoAssignListener.class);

    private final AssignmentService assignments;

    AutoAssignListener(AssignmentService assignments) {
        this.assignments = assignments;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onCreated(TicketEvents.Created event) {
        try {
            assignments.autoAssign(event.tenantId(), event.ticketId());
        } catch (RuntimeException e) {
            log.warn("Auto-assignment of ticket {} failed: {}", event.ticketId(), e.getMessage());
        }
    }
}
