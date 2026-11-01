package com.tickwise.shared.domain;

/** Ticket priority. Shared by the ticket module (which owns tickets) and the SLA module (which owns policies). */
public enum Priority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT
}
