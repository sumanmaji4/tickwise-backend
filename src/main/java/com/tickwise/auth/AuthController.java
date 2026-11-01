package com.tickwise.auth;

import com.tickwise.shared.security.AuthenticatedUser;
import com.tickwise.shared.web.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.Locale;
import com.tickwise.shared.security.Role;
import com.tickwise.tenant.TenantService;
import com.tickwise.tenant.TenantView;
import com.tickwise.tenant.UserService;
import com.tickwise.tenant.UserView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Auth")
class AuthController {

    /** Brute-force protection: attempts per client address, and per targeted account, per window. */
    static final int LOGIN_ATTEMPTS_PER_CLIENT = 20;
    static final int LOGIN_ATTEMPTS_PER_ACCOUNT = 10;
    static final Duration LOGIN_WINDOW = Duration.ofMinutes(5);

    private final AuthService auth;
    private final UserService users;
    private final TenantService tenants;
    private final RateLimiter rateLimiter;

    AuthController(AuthService auth, UserService users, TenantService tenants, RateLimiter rateLimiter) {
        this.auth = auth;
        this.users = users;
        this.tenants = tenants;
        this.rateLimiter = rateLimiter;
    }

    record LoginRequest(
            // Same shape as a tenant slug; also keeps attacker-controlled text out of the logs.
            @NotBlank @Size(max = 63) @Pattern(regexp = "[a-z0-9-]+", message = "must be a workspace slug") String tenantSlug,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 72) String password) {

        @Override
        public String toString() {
            return "LoginRequest[tenantSlug=" + tenantSlug + ", email=" + email + "]";
        }
    }

    record LoginResponse(String accessToken, String tokenType, Instant expiresAt) {}

    record MeResponse(UUID userId, UUID tenantId, String tenantSlug, String tenantName, String email,
                      String displayName, Role role, UUID teamId) {}

    @PostMapping("/api/auth/login")
    @SecurityRequirements
    @Operation(summary = "Exchange tenant, email and password for an access token")
    LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        // Limits are checked before the password, so a locked-out attacker gets no signal from it.
        rateLimiter.check("login-client:" + http.getRemoteAddr(), LOGIN_ATTEMPTS_PER_CLIENT, LOGIN_WINDOW);
        rateLimiter.check("login-account:" + request.tenantSlug() + ":" + request.email().strip().toLowerCase(Locale.ROOT),
                LOGIN_ATTEMPTS_PER_ACCOUNT, LOGIN_WINDOW);
        AuthService.LoginResult result = auth.login(request.tenantSlug(), request.email(), request.password());
        return new LoginResponse(result.token().value(), "Bearer", result.token().expiresAt());
    }

    @GetMapping("/api/me")
    @Operation(summary = "The authenticated user and tenant")
    MeResponse me(@AuthenticationPrincipal AuthenticatedUser caller) {
        UserView user = users.get(caller.tenantId(), caller.userId());
        TenantView tenant = tenants.get(caller.tenantId());
        return new MeResponse(user.id(), tenant.id(), tenant.slug(), tenant.name(), user.email(),
                user.displayName(), user.role(), user.teamId());
    }
}
