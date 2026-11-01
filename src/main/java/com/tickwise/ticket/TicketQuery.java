package com.tickwise.ticket;

import com.tickwise.shared.domain.Priority;
import java.util.Set;
import java.util.UUID;

/** Optional filters for the ticket queue; null means "any". */
public record TicketQuery(
        Set<TicketStatus> statuses,
        Set<Priority> priorities,
        UUID assigneeId,
        Boolean unassigned,
        UUID teamId,
        Boolean breached,
        String text) {

    public static TicketQuery all() {
        return new TicketQuery(null, null, null, null, null, null, null);
    }
}
