package com.tickwise.ticket;

import com.tickwise.shared.domain.Priority;
import java.time.Instant;
import java.util.UUID;

public record TicketView(
        long id,
        String subject,
        TicketStatus status,
        Priority priority,
        Channel channel,
        CustomerView customer,
        UUID assigneeId,
        String assigneeName,
        UUID teamId,
        Instant firstResponseDueAt,
        Instant resolutionDueAt,
        Instant nextSlaDueAt,
        Instant nextSlaWarningAt,
        Instant firstRespondedAt,
        Instant resolvedAt,
        boolean slaBreached,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    /** While waiting on the customer the SLA clocks are paused. */
    public boolean isSlaPaused() {
        return status == TicketStatus.PENDING_CUSTOMER;
    }

    public record CustomerView(UUID id, String email, String name) {}
}
