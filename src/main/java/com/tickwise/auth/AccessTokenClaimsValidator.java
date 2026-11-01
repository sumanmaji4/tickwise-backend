package com.tickwise.auth;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Rejects a correctly signed token whose identity claims cannot be parsed, so it yields 401, not 500. */
class AccessTokenClaimsValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID =
            new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Access token claims are invalid", null);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            AccessTokenClaims.parse(jwt);
            return OAuth2TokenValidatorResult.success();
        } catch (IllegalArgumentException e) {
            return OAuth2TokenValidatorResult.failure(INVALID);
        }
    }
}
