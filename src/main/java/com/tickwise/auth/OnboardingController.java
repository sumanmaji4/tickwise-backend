package com.tickwise.auth;

import com.tickwise.shared.web.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated onboarding. Invitation tokens travel in request bodies, never in URL paths,
 * so they do not end up in access logs or browser history beyond the accept link itself.
 */
@RestController
@Tag(name = "Auth")
@SecurityRequirements
class OnboardingController {

    static final int SIGNUPS_PER_CLIENT = 5;
    static final Duration SIGNUP_WINDOW = Duration.ofHours(1);
    static final int INVITE_ATTEMPTS_PER_CLIENT = 30;
    static final Duration INVITE_WINDOW = Duration.ofMinutes(10);

    private final OnboardingService onboarding;
    private final RateLimiter rateLimiter;

    OnboardingController(OnboardingService onboarding, RateLimiter rateLimiter) {
        this.onboarding = onboarding;
        this.rateLimiter = rateLimiter;
    }

    record SignupRequest(
            @NotBlank @Size(max = 200) String organizationName,
            @NotBlank @Size(min = 2, max = 63)
            @Pattern(regexp = "[a-z0-9]([a-z0-9-]*[a-z0-9])?", message = "lowercase letters, digits and inner hyphens only")
            String slug,
            @NotBlank @Size(max = 64) String timezone,
            @NotBlank @Size(max = 200) String adminName,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 12, max = 72) String password) {

        @Override
        public String toString() {
            return "SignupRequest[slug=" + slug + "]";
        }
    }

    record TokenRequest(@NotBlank @Size(max = 100) String token) {}

    record AcceptRequest(
            @NotBlank @Size(max = 100) String token,
            @NotBlank @Size(max = 200) String displayName,
            @NotBlank @Size(min = 12, max = 72) String password) {

        @Override
        public String toString() {
            return "AcceptRequest[displayName=" + displayName + "]";
        }
    }

    record SessionResponse(String accessToken, String tokenType, java.time.Instant expiresAt, String tenantSlug) {}

    @PostMapping("/api/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an organisation with its first admin and sign in")
    SessionResponse signUp(@Valid @RequestBody SignupRequest request, HttpServletRequest http) {
        rateLimiter.check("signup:" + http.getRemoteAddr(), SIGNUPS_PER_CLIENT, SIGNUP_WINDOW);
        return session(onboarding.signUp(request.organizationName(), request.slug(), request.timezone(),
                request.adminName(), request.email(), request.password()));
    }

    @PostMapping("/api/auth/invitations/preview")
    @Operation(summary = "Who an invitation is for (404 if invalid, expired, revoked or used)")
    OnboardingService.InvitationPreview preview(@Valid @RequestBody TokenRequest request, HttpServletRequest http) {
        rateLimiter.check("invite:" + http.getRemoteAddr(), INVITE_ATTEMPTS_PER_CLIENT, INVITE_WINDOW);
        return onboarding.preview(request.token());
    }

    @PostMapping("/api/auth/invitations/accept")
    @Operation(summary = "Accept an invitation, set a password and sign in")
    SessionResponse accept(@Valid @RequestBody AcceptRequest request, HttpServletRequest http) {
        rateLimiter.check("invite:" + http.getRemoteAddr(), INVITE_ATTEMPTS_PER_CLIENT, INVITE_WINDOW);
        return session(onboarding.acceptInvitation(request.token(), request.displayName(), request.password()));
    }

    private static SessionResponse session(OnboardingService.SignedIn signedIn) {
        return new SessionResponse(signedIn.token().value(), "Bearer", signedIn.token().expiresAt(), signedIn.tenant().slug());
    }
}
