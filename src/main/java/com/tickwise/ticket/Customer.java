package com.tickwise.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** The external requester of a ticket, identified by email within a tenant. */
@Entity
@Table(name = "customers")
class Customer {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String email;

    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Customer() {}

    Customer(UUID tenantId, String email, String name, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.email = email;
        this.name = name;
        this.createdAt = createdAt;
    }

    UUID getId() {
        return id;
    }

    String getEmail() {
        return email;
    }

    String getName() {
        return name;
    }

    void rememberName(String name) {
        if (this.name == null && name != null && !name.isBlank()) {
            this.name = name.strip();
        }
    }
}
