package com.tickwise.tenant;

import com.tickwise.shared.security.Role;
import java.time.Instant;
import java.util.UUID;

public record UserView(
        UUID id,
        UUID tenantId,
        String email,
        String displayName,
        Role role,
        UUID teamId,
        int maxOpenTickets,
        boolean active,
        Instant createdAt) {}
