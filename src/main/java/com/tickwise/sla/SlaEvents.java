package com.tickwise.sla;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Domain events published by the SLA module, inside the transaction that caused them. */
public final class SlaEvents {

    private SlaEvents() {}

    /** {@value SlaClock#WARNING_PERCENT}% of the target has been consumed. */
    public record Warning(UUID tenantId, long ticketId, SlaMetric metric, Instant dueAt) {}

    /** The deadline passed without the target being met. Fired at most once per ticket and metric. */
    public record Breached(UUID tenantId, long ticketId, SlaMetric metric, Instant dueAt) {}

    /** A target was met (or the ticket closed); {@code consumed} is the business time it took. */
    public record Stopped(UUID tenantId, long ticketId, SlaMetric metric, Duration consumed, boolean breached, Instant at) {}
}
