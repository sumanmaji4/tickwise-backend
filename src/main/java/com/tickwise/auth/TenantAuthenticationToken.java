package com.tickwise.auth;

import com.tickwise.shared.security.AuthenticatedUser;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Authentication whose principal is the {@link AuthenticatedUser} read from a verified JWT. */
class TenantAuthenticationToken extends AbstractAuthenticationToken {

    private final AuthenticatedUser principal;
    private final transient Jwt jwt;

    TenantAuthenticationToken(AuthenticatedUser principal, Jwt jwt) {
        super(List.of(new SimpleGrantedAuthority(principal.role().authority())));
        this.principal = principal;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    static TenantAuthenticationToken from(Jwt jwt) {
        return new TenantAuthenticationToken(AccessTokenClaims.parse(jwt), jwt);
    }

    /** The user id; also the name used for per-user WebSocket destinations. */
    @Override
    public String getName() {
        return principal.userId().toString();
    }

    @Override
    public AuthenticatedUser getPrincipal() {
        return principal;
    }

    @Override
    public Jwt getCredentials() {
        return jwt;
    }
}
