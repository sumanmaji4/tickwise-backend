package com.tickwise.sla.escalation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** A delay queue of escalation jobs. Delivery is at-least-once; handlers must be idempotent. */
public interface EscalationQueue {

    /** Schedules {@code job} at {@code dueAt}, replacing any existing schedule for the same job. */
    void schedule(EscalationJob job, Instant dueAt);

    void cancel(EscalationJob job);

    /**
     * Claims up to {@code limit} jobs due at {@code now}. A claimed job is hidden for {@code lease}
     * and is redelivered after that unless {@link #complete completed} or re-scheduled.
     */
    List<Claimed> claimDue(Instant now, int limit, Duration lease);

    /** Removes a claimed job, unless it was re-scheduled while being processed. */
    void complete(Claimed claimed);

    record Claimed(EscalationJob job, long leaseScore) {}
}
