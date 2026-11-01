package com.tickwise.reporting;

import com.tickwise.sla.SlaEvents;
import com.tickwise.sla.SlaMetric;
import com.tickwise.ticket.TicketEvents;
import com.tickwise.ticket.TicketStatus;
import java.sql.Timestamp;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Maintains {@code ticket_metrics}, the reporting read model, from domain events. Listeners run
 * in the publishing transaction, so the projection can never disagree with what committed.
 * Reports query only this table, never another module's tables.
 */
@Component
class TicketMetricsProjection {

    private final JdbcTemplate jdbc;

    TicketMetricsProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    void on(TicketEvents.Created e) {
        jdbc.update("""
                insert into ticket_metrics (tenant_id, ticket_id, team_id, priority, channel, is_open, created_at)
                values (?, ?, ?, ?, ?, true, ?) on conflict do nothing""",
                e.tenantId(), e.ticketId(), e.teamId(), e.priority().name(), e.channel().name(), Timestamp.from(e.createdAt()));
    }

    @EventListener
    void on(TicketEvents.Assigned e) {
        jdbc.update("update ticket_metrics set assignee_id = ?, team_id = ? where tenant_id = ? and ticket_id = ?",
                e.assigneeId(), e.teamId(), e.tenantId(), e.ticketId());
    }

    @EventListener
    void on(TicketEvents.PriorityChanged e) {
        jdbc.update("update ticket_metrics set priority = ? where tenant_id = ? and ticket_id = ?",
                e.to().name(), e.tenantId(), e.ticketId());
    }

    @EventListener
    void on(TicketEvents.StatusChanged e) {
        if (e.from() == TicketStatus.RESOLVED && e.to() == TicketStatus.OPEN) {
            // Reopened: resolution is measured again when it is next resolved.
            jdbc.update("""
                    update ticket_metrics set is_open = true, resolved_at = null, resolution_seconds = null
                    where tenant_id = ? and ticket_id = ?""", e.tenantId(), e.ticketId());
            return;
        }
        jdbc.update("update ticket_metrics set is_open = ? where tenant_id = ? and ticket_id = ?",
                e.to().isActive(), e.tenantId(), e.ticketId());
    }

    /** Deleted tickets leave the reports entirely, as if they had never been opened. */
    @EventListener
    void on(TicketEvents.Deleted e) {
        jdbc.update("delete from ticket_metrics where tenant_id = ? and ticket_id = ?", e.tenantId(), e.ticketId());
    }

    @EventListener
    void on(SlaEvents.Stopped e) {
        if (e.metric() == SlaMetric.FIRST_RESPONSE) {
            jdbc.update("""
                    update ticket_metrics set first_responded_at = ?, first_response_seconds = ?,
                           first_response_breached = first_response_breached or ?
                    where tenant_id = ? and ticket_id = ?""",
                    Timestamp.from(e.at()), e.consumed().toSeconds(), e.breached(), e.tenantId(), e.ticketId());
        } else {
            jdbc.update("""
                    update ticket_metrics set resolved_at = ?, resolution_seconds = ?,
                           resolution_breached = resolution_breached or ?
                    where tenant_id = ? and ticket_id = ?""",
                    Timestamp.from(e.at()), e.consumed().toSeconds(), e.breached(), e.tenantId(), e.ticketId());
        }
    }

    @EventListener
    void on(SlaEvents.Breached e) {
        String column = e.metric() == SlaMetric.FIRST_RESPONSE ? "first_response_breached" : "resolution_breached";
        jdbc.update("update ticket_metrics set " + column + " = true where tenant_id = ? and ticket_id = ?",
                e.tenantId(), e.ticketId());
    }
}
