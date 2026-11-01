package com.tickwise.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only; a database trigger rejects UPDATE and DELETE on the table. */
@Entity
@Immutable
@Table(name = "ticket_audit")
class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "ticket_id", nullable = false)
    private long ticketId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> details;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditEntry() {}

    AuditEntry(UUID tenantId, long ticketId, UUID actorUserId, AuditType type, Map<String, Object> details, Instant createdAt) {
        this.tenantId = tenantId;
        this.ticketId = ticketId;
        this.actorUserId = actorUserId;
        this.type = type;
        this.details = details;
        this.createdAt = createdAt;
    }

    AuditEntryView toView() {
        return new AuditEntryView(id, type, actorUserId, details, createdAt);
    }
}
