package com.tickwise.ticket;

import java.util.EnumSet;
import java.util.Set;

/**
 * Ticket lifecycle. Allowed manual transitions:
 *
 * <pre>
 *   NEW ──► OPEN ◄──► PENDING_CUSTOMER
 *    │       │              │
 *    └───────┴──► RESOLVED ◄┘ ──► CLOSED (terminal)
 *                    │
 *                    └──► OPEN (reopen)
 * </pre>
 *
 * Any non-terminal status may also go straight to CLOSED.
 */
public enum TicketStatus {
    NEW,
    OPEN,
    PENDING_CUSTOMER,
    RESOLVED,
    CLOSED;

    public static final Set<TicketStatus> ACTIVE = EnumSet.of(NEW, OPEN, PENDING_CUSTOMER);

    public boolean canTransitionTo(TicketStatus target) {
        return switch (this) {
            case NEW -> target == OPEN || target == PENDING_CUSTOMER || target == RESOLVED || target == CLOSED;
            case OPEN -> target == PENDING_CUSTOMER || target == RESOLVED || target == CLOSED;
            case PENDING_CUSTOMER -> target == OPEN || target == RESOLVED || target == CLOSED;
            case RESOLVED -> target == OPEN || target == CLOSED;
            case CLOSED -> false;
        };
    }

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    /** RESOLVED or CLOSED: the resolution target has been met. */
    public boolean isDone() {
        return this == RESOLVED || this == CLOSED;
    }
}
