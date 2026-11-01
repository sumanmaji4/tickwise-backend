package com.tickwise.ticket;

import com.tickwise.shared.domain.Times;
import com.tickwise.sla.SlaEvents;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Records SLA warnings and breaches on the ticket, in the transaction that detected them. */
@Component
class TicketSlaListener {

    private final TicketService tickets;
    private final TicketRepository repository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    TicketSlaListener(TicketService tickets, TicketRepository repository, ApplicationEventPublisher events, Clock clock) {
        this.tickets = tickets;
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    @EventListener
    void onWarning(SlaEvents.Warning event) {
        tickets.record(event.tenantId(), event.ticketId(), null, AuditType.SLA_WARNING, Times.now(clock),
                "metric", event.metric(), "dueAt", event.dueAt());
        events.publishEvent(new TicketEvents.SlaFlagged(event.tenantId(), event.ticketId(), false));
    }

    @EventListener
    void onBreach(SlaEvents.Breached event) {
        repository.markBreached(event.tenantId(), event.ticketId());
        tickets.record(event.tenantId(), event.ticketId(), null, AuditType.SLA_BREACHED, Times.now(clock),
                "metric", event.metric(), "dueAt", event.dueAt());
        events.publishEvent(new TicketEvents.SlaFlagged(event.tenantId(), event.ticketId(), true));
    }
}
