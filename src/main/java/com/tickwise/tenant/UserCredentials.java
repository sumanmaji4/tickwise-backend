package com.tickwise.tenant;

import com.tickwise.shared.security.Role;
import java.util.UUID;

/** What the auth module needs to verify a login. Never serialized to clients. */
public record UserCredentials(UUID userId, UUID tenantId, Role role, String passwordHash, boolean active) {

    @Override
    public String toString() {
        return "UserCredentials[userId=" + userId + ", tenantId=" + tenantId + ", role=" + role + "]";
    }
}
