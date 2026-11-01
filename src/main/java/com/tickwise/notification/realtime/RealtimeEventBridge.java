package com.tickwise.notification.realtime;

import com.tickwise.notification.NotificationEvents;
import com.tickwise.ticket.TicketEvents;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Pushes committed changes only: a rolled-back change must never appear on a dashboard. */
@Component
class RealtimeEventBridge {

    private final RealtimePublisher publisher;

    RealtimeEventBridge(RealtimePublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onTicketChange(TicketEvents.TicketChange event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticketId", event.ticketId());
        if (event instanceof TicketEvents.Assigned assigned && assigned.assigneeId() != null) {
            payload.put("assigneeId", assigned.assigneeId().toString());
        }
        if (event instanceof TicketEvents.StatusChanged changed) {
            payload.put("status", changed.to().name());
        }
        publisher.publish(new RealtimeMessage(event.tenantId(), null, "ticket." + typeName(event), payload));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onNotification(NotificationEvents.Created event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", event.notification().id().toString());
        payload.put("type", event.notification().type());
        payload.put("message", event.notification().message());
        if (event.notification().ticketId() != null) {
            payload.put("ticketId", event.notification().ticketId());
        }
        publisher.publish(new RealtimeMessage(event.tenantId(), event.userId(), "notification", payload));
    }

    private static String typeName(TicketEvents.TicketChange event) {
        String simple = event.getClass().getSimpleName();
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }
}
