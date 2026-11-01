package com.tickwise.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tickwise.shared.security.AuthenticatedUser;
import com.tickwise.shared.security.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;

class JwtTokenServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789-abcdefghijkl";
    private static final AuthenticatedUser AGENT =
            new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), Role.AGENT);

    private final JwtConfig config = new JwtConfig();

    @Test
    void issuedTokenDecodesToTheSameUser() {
        SecurityProperties props = properties(SECRET, "tickwise");
        JwtTokenService.IssuedToken token = service(props, Clock.systemUTC()).issue(AGENT);

        Jwt jwt = decoder(props).decode(token.value());

        assertThat(AccessTokenClaims.parse(jwt)).isEqualTo(AGENT);
        assertThat(jwt.getExpiresAt()).isEqualTo(token.expiresAt());
        assertThat(TenantAuthenticationToken.from(jwt).getAuthorities())
                .extracting(Object::toString).containsExactly("ROLE_AGENT");
    }

    @Test
    void expiryHonoursConfiguredTtl() {
        Instant now = Instant.parse("2026-03-01T10:00:00Z");
        JwtTokenService.IssuedToken token =
                service(properties(SECRET, "tickwise"), Clock.fixed(now, ZoneOffset.UTC)).issue(AGENT);

        assertThat(token.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(30)));
    }

    @Test
    void expiredTokenIsRejected() {
        SecurityProperties props = properties(SECRET, "tickwise");
        Clock twoHoursAgo = Clock.offset(Clock.systemUTC(), Duration.ofHours(-2));
        String token = service(props, twoHoursAgo).issue(AGENT).value();

        assertThatThrownBy(() -> decoder(props).decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String forged = service(properties("another-secret-0123456789-abcdefghijklm", "tickwise"), Clock.systemUTC())
                .issue(AGENT).value();

        assertThatThrownBy(() -> decoder(properties(SECRET, "tickwise")).decode(forged))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        String token = service(properties(SECRET, "someone-else"), Clock.systemUTC()).issue(AGENT).value();

        assertThatThrownBy(() -> decoder(properties(SECRET, "tickwise")).decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void correctlySignedTokenWithoutTenantClaimIsRejected() {
        SecurityProperties props = properties(SECRET, "tickwise");
        JwtEncoder encoder = config.jwtEncoder(config.jwtSigningKey(props));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("tickwise").subject(UUID.randomUUID().toString())
                .issuedAt(now).expiresAt(now.plusSeconds(600))
                .claim(AccessTokenClaims.ROLE, "ADMIN")
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        assertThatThrownBy(() -> decoder(props).decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("claims are invalid");
    }

    @Test
    void unknownRoleClaimIsRejected() {
        SecurityProperties props = properties(SECRET, "tickwise");
        JwtEncoder encoder = config.jwtEncoder(config.jwtSigningKey(props));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("tickwise").subject(UUID.randomUUID().toString())
                .issuedAt(now).expiresAt(now.plusSeconds(600))
                .claim(AccessTokenClaims.TENANT_ID, UUID.randomUUID().toString())
                .claim(AccessTokenClaims.ROLE, "SUPERUSER")
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        assertThatThrownBy(() -> decoder(props).decode(token)).isInstanceOf(JwtValidationException.class);
    }

    private JwtTokenService service(SecurityProperties props, Clock clock) {
        SecretKey key = config.jwtSigningKey(props);
        return new JwtTokenService(config.jwtEncoder(key), props, clock);
    }

    private JwtDecoder decoder(SecurityProperties props) {
        return config.jwtDecoder(config.jwtSigningKey(props), props);
    }

    private static SecurityProperties properties(String secret, String issuer) {
        return new SecurityProperties(new SecurityProperties.Jwt(secret, issuer, Duration.ofMinutes(30)), List.of());
    }
}
