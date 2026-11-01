package com.tickwise.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "messages")
class Message {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "ticket_id", nullable = false, updatable = false)
    private long ticketId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private MessageDirection direction;

    @Column(name = "author_user_id", updatable = false)
    private UUID authorUserId;

    @Column(name = "author_customer_id", updatable = false)
    private UUID authorCustomerId;

    @Column(nullable = false, updatable = false)
    private String body;

    @Column(name = "email_message_id")
    private String emailMessageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Message() {}

    private Message(UUID tenantId, long ticketId, MessageDirection direction, UUID authorUserId,
                    UUID authorCustomerId, String body, String emailMessageId, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.ticketId = ticketId;
        this.direction = direction;
        this.authorUserId = authorUserId;
        this.authorCustomerId = authorCustomerId;
        this.body = body;
        this.emailMessageId = emailMessageId;
        this.createdAt = createdAt;
    }

    static Message fromCustomer(UUID tenantId, long ticketId, UUID customerId, String body, String emailMessageId, Instant now) {
        return new Message(tenantId, ticketId, MessageDirection.INBOUND, null, customerId, body, emailMessageId, now);
    }

    static Message fromAgent(UUID tenantId, long ticketId, UUID agentId, String body, Instant now) {
        return new Message(tenantId, ticketId, MessageDirection.OUTBOUND, agentId, null, body, null, now);
    }

    void assignEmailMessageId(String emailMessageId) {
        this.emailMessageId = emailMessageId;
    }

    UUID getId() { return id; }
    long getTicketId() { return ticketId; }
    MessageDirection getDirection() { return direction; }
    UUID getAuthorUserId() { return authorUserId; }
    UUID getAuthorCustomerId() { return authorCustomerId; }
    String getBody() { return body; }
    String getEmailMessageId() { return emailMessageId; }
    Instant getCreatedAt() { return createdAt; }
}
