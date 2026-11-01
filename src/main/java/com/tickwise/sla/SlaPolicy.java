package com.tickwise.sla;

import com.tickwise.shared.domain.Priority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity
@Table(name = "sla_policies")
class SlaPolicy {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Priority priority;

    @Column(name = "first_response_minutes", nullable = false)
    private int firstResponseMinutes;

    @Column(name = "resolution_minutes", nullable = false)
    private int resolutionMinutes;

    @Version
    private Long version;

    protected SlaPolicy() {}

    SlaPolicy(UUID tenantId, Priority priority, int firstResponseMinutes, int resolutionMinutes) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.priority = priority;
        update(firstResponseMinutes, resolutionMinutes);
    }

    void update(int firstResponseMinutes, int resolutionMinutes) {
        this.firstResponseMinutes = firstResponseMinutes;
        this.resolutionMinutes = resolutionMinutes;
    }

    SlaPolicyView toView() {
        return new SlaPolicyView(priority, firstResponseMinutes, resolutionMinutes);
    }
}
