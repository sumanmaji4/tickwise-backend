package com.tickwise.ticket;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEntryView(long id, AuditType type, UUID actorUserId, Map<String, Object> details, Instant createdAt) {}
