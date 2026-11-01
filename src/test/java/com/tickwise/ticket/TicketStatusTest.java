package com.tickwise.ticket;

import static com.tickwise.ticket.TicketStatus.CLOSED;
import static com.tickwise.ticket.TicketStatus.NEW;
import static com.tickwise.ticket.TicketStatus.OPEN;
import static com.tickwise.ticket.TicketStatus.PENDING_CUSTOMER;
import static com.tickwise.ticket.TicketStatus.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TicketStatusTest {

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
            "NEW, OPEN, true", "NEW, PENDING_CUSTOMER, true", "NEW, RESOLVED, true", "NEW, CLOSED, true",
            "OPEN, NEW, false", "OPEN, PENDING_CUSTOMER, true", "OPEN, RESOLVED, true", "OPEN, CLOSED, true",
            "PENDING_CUSTOMER, OPEN, true", "PENDING_CUSTOMER, NEW, false", "PENDING_CUSTOMER, RESOLVED, true",
            "RESOLVED, OPEN, true", "RESOLVED, CLOSED, true", "RESOLVED, PENDING_CUSTOMER, false", "RESOLVED, NEW, false",
            "CLOSED, OPEN, false", "CLOSED, NEW, false", "CLOSED, RESOLVED, false",
    })
    void transitions(TicketStatus from, TicketStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @CsvSource({"NEW, true", "OPEN, true", "PENDING_CUSTOMER, true", "RESOLVED, false", "CLOSED, false"})
    void activeStatusesCountTowardsAgentCapacity(TicketStatus status, boolean active) {
        assertThat(status.isActive()).isEqualTo(active);
        assertThat(status.isDone()).isEqualTo(status == RESOLVED || status == CLOSED);
        assertThat(NEW.isDone() || OPEN.isDone() || PENDING_CUSTOMER.isDone() || !CLOSED.isDone()).isFalse();
    }
}
