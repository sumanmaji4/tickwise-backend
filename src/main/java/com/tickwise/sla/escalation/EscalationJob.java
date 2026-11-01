package com.tickwise.sla.escalation;

import com.tickwise.sla.SlaMetric;
import java.util.UUID;

/**
 * Identity of a delayed escalation. The job carries no deadline: the handler re-reads the clock
 * when it fires, so a stale job can never act on outdated state. Because the identity is
 * deterministic, re-scheduling overwrites the previous entry instead of adding a duplicate.
 */
public record EscalationJob(UUID tenantId, long ticketId, SlaMetric metric, EscalationKind kind) {

    private static final String SEPARATOR = "|";

    public String key() {
        return tenantId + SEPARATOR + ticketId + SEPARATOR + metric + SEPARATOR + kind;
    }

    public static EscalationJob parse(String key) {
        String[] parts = key.split("\\|");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Malformed escalation job key: " + key);
        }
        return new EscalationJob(UUID.fromString(parts[0]), Long.parseLong(parts[1]),
                SlaMetric.valueOf(parts[2]), EscalationKind.valueOf(parts[3]));
    }
}
