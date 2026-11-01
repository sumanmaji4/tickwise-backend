package com.tickwise.notification;

import com.tickwise.shared.security.Role;
import com.tickwise.sla.SlaEvents;
import com.tickwise.sla.SlaMetric;
import com.tickwise.tenant.UserService;
import com.tickwise.tenant.UserView;
import com.tickwise.ticket.TicketEvents;
import com.tickwise.ticket.TicketService;
import com.tickwise.ticket.TicketView;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Who hears about what:
 * <ul>
 *   <li>WARNING goes to the assignee, or to the team's managers if nobody owns the ticket.</li>
 *   <li>BREACH goes to the team's managers, or to every manager in the tenant if the ticket has no team.</li>
 *   <li>Assignment goes to the new assignee, unless they assigned the ticket to themselves.</li>
 * </ul>
 * Runs in the transaction that raised the event, so a notification exists only if that change committed.
 */
@Component
class EscalationNotifier {

    private final NotificationService notifications;
    private final TicketService tickets;
    private final UserService users;

    EscalationNotifier(NotificationService notifications, TicketService tickets, UserService users) {
        this.notifications = notifications;
        this.tickets = tickets;
        this.users = users;
    }

    @EventListener
    void onWarning(SlaEvents.Warning event) {
        TicketView ticket = tickets.get(event.tenantId(), event.ticketId());
        String message = "SLA warning: " + label(event.metric()) + " for #" + ticket.id() + " \"" + ticket.subject()
                + "\" is due soon";
        Set<UUID> recipients = ticket.assigneeId() != null
                ? Set.of(ticket.assigneeId())
                : managersOf(event.tenantId(), ticket);
        recipients.forEach(u -> notifications.notify(event.tenantId(), u, Notification.Type.SLA_WARNING, ticket.id(), message));
    }

    @EventListener
    void onBreach(SlaEvents.Breached event) {
        TicketView ticket = tickets.get(event.tenantId(), event.ticketId());
        String owner = ticket.assigneeName() == null ? "unassigned" : "assigned to " + ticket.assigneeName();
        String message = "SLA breached: " + label(event.metric()) + " for #" + ticket.id() + " \"" + ticket.subject()
                + "\" (" + owner + ")";
        managersOf(event.tenantId(), ticket).forEach(u ->
                notifications.notify(event.tenantId(), u, Notification.Type.SLA_BREACHED, ticket.id(), message));
    }

    @EventListener
    void onAssigned(TicketEvents.Assigned event) {
        if (event.assigneeId() == null || Objects.equals(event.assigneeId(), event.actorUserId())) {
            return;
        }
        TicketView ticket = tickets.get(event.tenantId(), event.ticketId());
        notifications.notify(event.tenantId(), event.assigneeId(), Notification.Type.TICKET_ASSIGNED, ticket.id(),
                "Ticket #" + ticket.id() + " \"" + ticket.subject() + "\" was assigned to you");
    }

    private Set<UUID> managersOf(UUID tenantId, TicketView ticket) {
        List<UserView> managers = ticket.teamId() == null ? List.of()
                : users.activeTeamMembers(tenantId, ticket.teamId(), Set.of(Role.MANAGER));
        if (managers.isEmpty()) {
            managers = users.activeWithRoles(tenantId, Set.of(Role.MANAGER));
        }
        Set<UUID> ids = new LinkedHashSet<>();
        managers.forEach(m -> ids.add(m.id()));
        return ids;
    }

    private static String label(SlaMetric metric) {
        return metric == SlaMetric.FIRST_RESPONSE ? "first response" : "resolution";
    }
}
