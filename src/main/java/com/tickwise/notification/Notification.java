package com.tickwise.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
class Notification {

    enum Type { SLA_WARNING, SLA_BREACHED, TICKET_ASSIGNED }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Type type;

    @Column(name = "ticket_id", updatable = false)
    private Long ticketId;

    @Column(nullable = false, updatable = false)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {}

    Notification(UUID tenantId, UUID userId, Type type, Long ticketId, String message, Instant now) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.userId = userId;
        this.type = type;
        this.ticketId = ticketId;
        this.message = message.length() <= 500 ? message : message.substring(0, 500);
        this.createdAt = now;
    }

    void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }

    UUID getTenantId() { return tenantId; }
    UUID getUserId() { return userId; }

    NotificationView toView() {
        return new NotificationView(id, type.name(), ticketId, message, createdAt, readAt);
    }
}
