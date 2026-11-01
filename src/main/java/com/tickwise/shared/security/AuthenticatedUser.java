package com.tickwise.shared.security;

import java.util.Objects;
import java.util.UUID;

/**
 * The caller, as established from a verified access token. This is the only source of the
 * tenant for a request: controllers take it via {@code @AuthenticationPrincipal} and pass
 * {@link #tenantId()} down; tenant ids are never read from request parameters or bodies.
 */
public record AuthenticatedUser(UUID userId, UUID tenantId, Role role) {

    public AuthenticatedUser {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(role, "role");
    }
}
