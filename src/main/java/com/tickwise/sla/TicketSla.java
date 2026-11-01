package com.tickwise.sla;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Persistent form of an {@link SlaClock} plus the escalation markers that make jobs idempotent. */
@Entity
@Table(name = "ticket_sla")
class TicketSla {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "ticket_id", nullable = false, updatable = false)
    private long ticketId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private SlaMetric metric;

    @Column(name = "target_ms", nullable = false)
    private long targetMs;

    @Column(name = "consumed_ms", nullable = false)
    private long consumedMs;

    @Column(name = "running_since")
    private Instant runningSince;

    @Column(name = "met_at")
    private Instant metAt;

    @Column(name = "warned_at")
    private Instant warnedAt;

    @Column(name = "breached_at")
    private Instant breachedAt;

    @Version
    private Long version;

    protected TicketSla() {}

    TicketSla(UUID tenantId, long ticketId, SlaMetric metric, SlaClock clock) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.ticketId = ticketId;
        this.metric = metric;
        apply(clock);
    }

    SlaClock clock() {
        return new SlaClock(Duration.ofMillis(targetMs), Duration.ofMillis(consumedMs), runningSince, metAt);
    }

    void apply(SlaClock clock) {
        this.targetMs = clock.target().toMillis();
        this.consumedMs = clock.consumed().toMillis();
        this.runningSince = clock.runningSince();
        this.metAt = clock.metAt();
    }

    UUID id() {
        return id;
    }

    UUID tenantId() {
        return tenantId;
    }

    long ticketId() {
        return ticketId;
    }

    SlaMetric metric() {
        return metric;
    }

    Instant warnedAt() {
        return warnedAt;
    }

    Instant breachedAt() {
        return breachedAt;
    }

    void markWarned(Instant at) {
        this.warnedAt = at;
    }

    /** A new deadline gets a new warning, e.g. after a priority change or a resume. */
    void clearWarning() {
        this.warnedAt = null;
    }

    void markBreached(Instant at) {
        this.breachedAt = at;
    }
}
