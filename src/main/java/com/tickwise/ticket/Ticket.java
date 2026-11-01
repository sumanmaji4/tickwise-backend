package com.tickwise.ticket;

import com.tickwise.shared.domain.Priority;
import com.tickwise.sla.SlaSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.SQLRestriction;
import java.time.Instant;
import java.util.UUID;

/**
 * Soft-deleted tickets are filtered out of every entity query, specification and count by the
 * restriction below, so no call site can forget to exclude them. The row and its audit trail remain.
 */
@Entity
@Table(name = "tickets")
@SQLRestriction("deleted_at is null")
class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Channel channel;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "team_id")
    private UUID teamId;

    @Column(name = "first_response_due_at")
    private Instant firstResponseDueAt;

    @Column(name = "resolution_due_at")
    private Instant resolutionDueAt;

    @Column(name = "next_sla_due_at")
    private Instant nextSlaDueAt;

    @Column(name = "next_sla_warning_at")
    private Instant nextSlaWarningAt;

    @Column(name = "first_responded_at")
    private Instant firstRespondedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "sla_breached", nullable = false)
    private boolean slaBreached;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;

    @Version
    private Long version;

    protected Ticket() {}

    void markDeleted(UUID actorId, Instant now) {
        this.deletedAt = now;
        this.deletedBy = actorId;
        touch(now);
    }

    Ticket(UUID tenantId, String subject, Priority priority, Channel channel, UUID customerId, UUID teamId, Instant now) {
        this.tenantId = tenantId;
        this.subject = subject;
        this.status = TicketStatus.NEW;
        this.priority = priority;
        this.channel = channel;
        this.customerId = customerId;
        this.teamId = teamId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void applySla(SlaSnapshot sla) {
        this.firstResponseDueAt = sla.firstResponse().dueAt();
        this.resolutionDueAt = sla.resolution().dueAt();
        this.nextSlaDueAt = sla.nextDueAt();
        this.nextSlaWarningAt = sla.nextWarningAt();
        this.slaBreached = slaBreached || sla.anyBreached();
    }

    void changeStatus(TicketStatus target, Instant now) {
        this.status = target;
        if (target.isDone() && resolvedAt == null) {
            resolvedAt = now;
        } else if (!target.isDone()) {
            resolvedAt = null;
        }
        touch(now);
    }

    void changePriority(Priority priority, Instant now) {
        this.priority = priority;
        touch(now);
    }

    void assign(UUID assigneeId, UUID teamId, Instant now) {
        this.assigneeId = assigneeId;
        if (teamId != null) {
            this.teamId = teamId;
        }
        touch(now);
    }

    /** Returns true if this was the first response. */
    boolean recordAgentReply(Instant now) {
        touch(now);
        if (firstRespondedAt == null) {
            firstRespondedAt = now;
            return true;
        }
        return false;
    }

    void touch(Instant now) {
        this.updatedAt = now;
    }

    Long getId() { return id; }
    UUID getTenantId() { return tenantId; }
    String getSubject() { return subject; }
    TicketStatus getStatus() { return status; }
    Priority getPriority() { return priority; }
    Channel getChannel() { return channel; }
    UUID getCustomerId() { return customerId; }
    UUID getAssigneeId() { return assigneeId; }
    UUID getTeamId() { return teamId; }
    Instant getFirstResponseDueAt() { return firstResponseDueAt; }
    Instant getResolutionDueAt() { return resolutionDueAt; }
    Instant getNextSlaDueAt() { return nextSlaDueAt; }
    Instant getNextSlaWarningAt() { return nextSlaWarningAt; }
    Instant getFirstRespondedAt() { return firstRespondedAt; }
    Instant getResolvedAt() { return resolvedAt; }
    boolean isSlaBreached() { return slaBreached; }
    Instant getCreatedAt() { return createdAt; }
    Instant getUpdatedAt() { return updatedAt; }
    Long getVersion() { return version; }
}
