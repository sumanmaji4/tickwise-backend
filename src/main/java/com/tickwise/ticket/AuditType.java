package com.tickwise.ticket;

public enum AuditType {
    CREATED,
    STATUS_CHANGED,
    PRIORITY_CHANGED,
    ASSIGNED,
    UNASSIGNED,
    MESSAGE_RECEIVED,
    REPLY_SENT,
    SLA_WARNING,
    SLA_BREACHED,
    DELETED
}
