package com.tickwise.auth;

import com.tickwise.shared.security.AuthenticatedUser;
import com.tickwise.shared.security.Role;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Claim names and parsing for Tickwise access tokens: {@code sub} = user id, {@code tid} = tenant id. */
final class AccessTokenClaims {

    static final String TENANT_ID = "tid";
    static final String ROLE = "role";

    private AccessTokenClaims() {}

    /** @throws IllegalArgumentException if a claim is missing or malformed */
    static AuthenticatedUser parse(Jwt jwt) {
        String subject = jwt.getSubject();
        String tenantId = jwt.getClaimAsString(TENANT_ID);
        String role = jwt.getClaimAsString(ROLE);
        if (subject == null || tenantId == null || role == null) {
            throw new IllegalArgumentException("Access token is missing required claims");
        }
        return new AuthenticatedUser(UUID.fromString(subject), UUID.fromString(tenantId), Role.valueOf(role));
    }
}
