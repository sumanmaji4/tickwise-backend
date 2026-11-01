package com.tickwise.tenant;

import com.tickwise.shared.security.AuthenticatedUser;
import com.tickwise.shared.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** User administration. Access is restricted to ADMIN by the security filter chain ({@code /api/admin/**}). */
@RestController
@RequestMapping("/api/admin/users")
@Tag(name = "Admin: users")
class AdminUserController {

    static final int DEFAULT_MAX_OPEN_TICKETS = 10;

    private final UserService users;

    AdminUserController(UserService users) {
        this.users = users;
    }

    record CreateUserRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 200) String displayName,
            // BCrypt only uses the first 72 bytes; reject longer input instead of silently truncating.
            @NotBlank @Size(min = 12, max = 72) String password,
            @NotNull Role role,
            UUID teamId,
            @Min(0) @Max(1000) Integer maxOpenTickets) {

        @Override
        public String toString() {
            return "CreateUserRequest[email=" + email + ", role=" + role + "]";
        }
    }

    @GetMapping
    @Operation(summary = "List the tenant's users")
    List<UserView> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return users.list(caller.tenantId());
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Get a user")
    UserView get(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID userId) {
        return users.get(caller.tenantId(), userId);
    }

    @PostMapping
    @Operation(summary = "Create a user")
    ResponseEntity<UserView> create(@AuthenticationPrincipal AuthenticatedUser caller,
                                    @Valid @RequestBody CreateUserRequest request) {
        int capacity = request.maxOpenTickets() != null ? request.maxOpenTickets() : DEFAULT_MAX_OPEN_TICKETS;
        UserView user = users.create(caller.tenantId(), new NewUser(request.email(), request.displayName(),
                request.password(), request.role(), request.teamId(), capacity));
        return ResponseEntity.created(URI.create("/api/admin/users/" + user.id())).body(user);
    }
}
