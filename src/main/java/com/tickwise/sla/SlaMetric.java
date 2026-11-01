package com.tickwise.sla;

public enum SlaMetric {
    /** Met by the first outbound agent reply. */
    FIRST_RESPONSE,
    /** Met when the ticket is resolved (or closed); resumes if the ticket is reopened. */
    RESOLUTION
}
