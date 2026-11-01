package com.tickwise.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bound from {@code tickwise.security.*}. Startup fails if the JWT secret is missing or too short. */
@Validated
@ConfigurationProperties("tickwise.security")
record SecurityProperties(@Valid @NotNull Jwt jwt, List<String> corsAllowedOrigins) {

    SecurityProperties {
        corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : List.copyOf(corsAllowedOrigins);
    }

    record Jwt(
            // HS256 needs a key of at least 256 bits.
            @NotBlank(message = "TICKWISE_JWT_SECRET must be set") @Size(min = 32, message = "must be at least 32 characters") String secret,
            @NotBlank String issuer,
            @NotNull Duration ttl) {

        @Override
        public String toString() {
            return "Jwt[issuer=" + issuer + ", ttl=" + ttl + "]";
        }
    }
}
