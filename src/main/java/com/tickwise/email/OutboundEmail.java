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

/** Transactional outbox row: written with the reply, delivered later by the worker. */
@Entity
@Table(name = "outbound_emails")
class OutboundEmail {

    enum Status { PENDING, SENT, FAILED }

    static final int MAX_ATTEMPTS = 6;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "ticket_id", updatable = false)
    private Long ticketId;

    @Column(name = "message_id", updatable = false)
    private UUID messageId;

    @Column(name = "to_address", nullable = false, updatable = false)
    private String toAddress;

    @Column(name = "to_name", updatable = false)
    private String toName;

    @Column(name = "reply_to", nullable = false, updatable = false)
    private String replyTo;

    @Column(nullable = false, updatable = false)
    private String subject;

    @Column(nullable = false)
    private String body;

    @Column(name = "email_message_id", nullable = false, updatable = false)
    private String emailMessageId;

    @Column(name = "in_reply_to", updatable = false)
    private String inReplyTo;

    @Column(name = "references_header", updatable = false)
    private String referencesHeader;

    @Column(name = "auto_submitted", nullable = false, updatable = false)
    private boolean autoSubmitted;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboundEmail() {}

    OutboundEmail(UUID tenantId, Long ticketId, UUID messageId, String toAddress, String toName, String replyTo,
                  String subject, String body, String emailMessageId, String inReplyTo, String referencesHeader,
                  boolean autoSubmitted, Instant now) {
        this.tenantId = tenantId;
        this.ticketId = ticketId;
        this.messageId = messageId;
        this.toAddress = toAddress;
        this.toName = toName;
        this.replyTo = replyTo;
        this.subject = subject;
        this.body = body;
        this.emailMessageId = emailMessageId;
        this.inReplyTo = inReplyTo;
        this.referencesHeader = referencesHeader;
        this.autoSubmitted = autoSubmitted;
        this.status = Status.PENDING;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    void markSent(Instant now) {
        status = Status.SENT;
        sentAt = now;
        lastError = null;
        if (ticketId == null) {
            // Invitation emails carry a single-use secret link; once delivered it must not linger in the database.
            body = "[invitation link removed after delivery]";
        }
    }

    void failAttempt(String error, Instant now) {
        attempts++;
        lastError = error == null || error.length() <= 500 ? error : error.substring(0, 500);
        if (attempts >= MAX_ATTEMPTS) {
            status = Status.FAILED;
        } else {
            nextAttemptAt = now.plus(Duration.ofSeconds(15L << attempts));
        }
    }

    EmailSender.OutgoingEmail toOutgoing(String fromAddress, String fromName) {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Message-ID", emailMessageId);
        if (inReplyTo != null) {
            headers.put("In-Reply-To", inReplyTo);
        }
        if (referencesHeader != null && !referencesHeader.isBlank()) {
            headers.put("References", referencesHeader);
        }
        if (autoSubmitted) {
            // RFC 3834: tells well-behaved auto-responders not to reply to this message.
            headers.put("Auto-Submitted", "auto-replied");
        }
        return new EmailSender.OutgoingEmail(fromAddress, fromName, toAddress, toName, replyTo, subject, body, headers);
    }

    Long getId() { return id; }
    UUID getTenantId() { return tenantId; }
    Status getStatus() { return status; }
    int getAttempts() { return attempts; }
    String getSubject() { return subject; }
    String getBody() { return body; }
    String getReplyTo() { return replyTo; }
    String getToAddress() { return toAddress; }
    String getEmailMessageId() { return emailMessageId; }
    String getInReplyTo() { return inReplyTo; }
    boolean isAutoSubmitted() { return autoSubmitted; }
    Long getTicketId() { return ticketId; }
}
