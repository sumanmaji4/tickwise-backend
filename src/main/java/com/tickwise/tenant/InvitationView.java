package com.tickwise.tenant;

import com.tickwise.shared.security.Role;
import java.time.Instant;
import java.util.UUID;

public record InvitationView(UUID id, String email, Role role, UUID teamId, int maxOpenTickets, UUID invitedBy,
                             Instant createdAt, Instant expiresAt) {}
