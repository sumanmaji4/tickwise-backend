package com.tickwise.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One parsed email from the provider webhook, queued for the worker. */
@Entity
@Table(name = "inbound_emails")
class InboundEmail {

    enum Status { PENDING, PROCESSED, IGNORED, DUPLICATE, FAILED }

    static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "ticket_id")
    private Long ticketId;

    private String outcome;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected InboundEmail() {}

    InboundEmail(String payload, Instant now) {
        this.payload = payload;
        this.receivedAt = now;
        this.status = Status.PENDING;
        this.nextAttemptAt = now;
    }

    void finish(Status status, UUID tenantId, Long ticketId, String outcome, Instant now) {
        this.status = status;
        this.tenantId = tenantId;
        this.ticketId = ticketId;
        this.outcome = truncate(outcome);
        this.processedAt = now;
    }

    /** Exponential backoff; gives up after {@link #MAX_ATTEMPTS}. */
    void failAttempt(String error, Instant now) {
        attempts++;
        outcome = truncate(error);
        if (attempts >= MAX_ATTEMPTS) {
            status = Status.FAILED;
            processedAt = now;
        } else {
            nextAttemptAt = now.plus(Duration.ofSeconds(10L << attempts));
        }
    }

    Long getId() { return id; }
    String getPayload() { return payload; }
    Status getStatus() { return status; }
    int getAttempts() { return attempts; }
    Long getTicketId() { return ticketId; }
    String getOutcome() { return outcome; }

    private static String truncate(String s) {
        return s == null || s.length() <= 300 ? s : s.substring(0, 300);
    }
}
