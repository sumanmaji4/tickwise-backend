package com.tickwise.tenant;

import com.tickwise.shared.security.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
class UserAccount {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    // Plain column rather than an association: the composite FK (tenant_id, team_id) in the
    // schema guarantees the team belongs to the same tenant.
    @Column(name = "team_id")
    private UUID teamId;

    @Column(name = "max_open_tickets", nullable = false)
    private int maxOpenTickets;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected UserAccount() {}

    UserAccount(UUID id, UUID tenantId, String email, String displayName, String passwordHash,
                Role role, UUID teamId, int maxOpenTickets, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = role;
        this.teamId = teamId;
        this.maxOpenTickets = maxOpenTickets;
        this.active = true;
        this.createdAt = createdAt;
    }

    UserView toView() {
        return new UserView(id, tenantId, email, displayName, role, teamId, maxOpenTickets, active, createdAt);
    }

    UserCredentials toCredentials() {
        return new UserCredentials(id, tenantId, role, passwordHash, active);
    }
}
