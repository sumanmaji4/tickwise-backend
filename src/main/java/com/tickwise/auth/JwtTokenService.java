package com.tickwise.auth;

import com.tickwise.shared.security.AuthenticatedUser;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
class JwtTokenService {

    record IssuedToken(String value, Instant expiresAt) {}

    private final JwtEncoder encoder;
    private final SecurityProperties.Jwt settings;
    private final Clock clock;

    JwtTokenService(JwtEncoder encoder, SecurityProperties properties, Clock clock) {
        this.encoder = encoder;
        this.settings = properties.jwt();
        this.clock = clock;
    }

    IssuedToken issue(AuthenticatedUser user) {
        // JWT NumericDate has whole-second precision; truncate so the reported expiry equals the token's.
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(settings.ttl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(settings.issuer())
                .subject(user.userId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(AccessTokenClaims.TENANT_ID, user.tenantId().toString())
                .claim(AccessTokenClaims.ROLE, user.role().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }
}
