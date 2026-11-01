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
@Table(name = "invitations")
class Invitation {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Role role;

    @Column(name = "team_id", updatable = false)
    private UUID teamId;

    @Column(name = "max_open_tickets", nullable = false, updatable = false)
    private int maxOpenTickets;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "invited_by", nullable = false, updatable = false)
    private UUID invitedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    private Long version;

    protected Invitation() {}

    Invitation(UUID tenantId, String email, Role role, UUID teamId, int maxOpenTickets, String tokenHash,
               UUID invitedBy, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.email = email;
        this.role = role;
        this.teamId = teamId;
        this.maxOpenTickets = maxOpenTickets;
        this.tokenHash = tokenHash;
        this.invitedBy = invitedBy;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    boolean isUsable(Instant now) {
        return acceptedAt == null && revokedAt == null && now.isBefore(expiresAt);
    }

    void accept(Instant now) {
        this.acceptedAt = now;
    }

    void revoke(Instant now) {
        this.revokedAt = now;
    }

    UUID getTenantId() { return tenantId; }
    String getEmail() { return email; }
    Role getRole() { return role; }
    UUID getTeamId() { return teamId; }
    int getMaxOpenTickets() { return maxOpenTickets; }

    InvitationView toView() {
        return new InvitationView(id, email, role, teamId, maxOpenTickets, invitedBy, createdAt, expiresAt);
    }
}
