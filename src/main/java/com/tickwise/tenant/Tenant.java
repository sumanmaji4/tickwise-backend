package com.tickwise.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

@Entity
@Table(name = "tenants")
class Tenant {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String timezone;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Assigned ids: a null version is how Spring Data tells a new entity from a detached one.
    @Version
    private Long version;

    protected Tenant() {}

    Tenant(UUID id, String slug, String name, ZoneId timezone, Instant createdAt) {
        this.id = id;
        this.slug = slug;
        this.name = name;
        this.timezone = timezone.getId();
        this.createdAt = createdAt;
    }

    UUID getId() {
        return id;
    }

    void changeTimezone(ZoneId zone) {
        this.timezone = zone.getId();
    }

    TenantView toView() {
        return new TenantView(id, slug, name, ZoneId.of(timezone));
    }
}
