package com.tickwise.auth;

import com.tickwise.shared.security.AuthenticatedUser;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Verifies a bearer token outside the HTTP filter chain, e.g. in a STOMP CONNECT frame (browsers
 * cannot set an Authorization header on the WebSocket handshake).
 */
@Component
public class AccessTokenVerifier {

    private final JwtDecoder decoder;

    AccessTokenVerifier(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    /** @throws BadCredentialsException if the token is missing, malformed, expired or forged */
    public AuthenticatedUser verify(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new BadCredentialsException("Missing bearer token");
        }
        try {
            return AccessTokenClaims.parse(decoder.decode(authorizationHeader.substring(7).strip()));
        } catch (JwtException | IllegalArgumentException e) {
            throw new BadCredentialsException("Invalid bearer token", e);
        }
    }
}
