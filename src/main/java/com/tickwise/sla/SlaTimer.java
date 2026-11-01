package com.tickwise.sla;

import java.time.Instant;

/** Read model of one SLA target for API responses and ticket denormalisation. */
public record SlaTimer(
        SlaMetric metric,
        State state,
        long targetMinutes,
        long consumedSeconds,
        Instant dueAt,
        Instant warningAt,
        Instant metAt,
        boolean breached,
        Instant breachedAt) {

    public enum State { RUNNING, PAUSED, MET }
}
