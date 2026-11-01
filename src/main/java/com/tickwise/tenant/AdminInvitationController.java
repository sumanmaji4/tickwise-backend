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
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Invite agents, managers and admins by email. ADMIN only via {@code /api/admin/**}. */
@RestController
@RequestMapping("/api/admin/invitations")
@Tag(name = "Admin: invitations")
class AdminInvitationController {

    private final InvitationService invitations;
    private final String frontendUrl;

    AdminInvitationController(InvitationService invitations, @Value("${tickwise.frontend-url}") String frontendUrl) {
        this.invitations = invitations;
        this.frontendUrl = frontendUrl;
    }

    record InviteRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotNull Role role,
            UUID teamId,
            @Min(0) @Max(1000) Integer maxOpenTickets) {}

    /** {@code inviteUrl} is returned once, so an admin can also share the link directly. */
    record InviteResponse(InvitationView invitation, String inviteUrl) {}

    @GetMapping
    @Operation(summary = "Open invitations")
    List<InvitationView> pending(@AuthenticationPrincipal AuthenticatedUser caller) {
        return invitations.pending(caller.tenantId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Invite someone by email (replaces any open invitation for that address)")
    InviteResponse invite(@AuthenticationPrincipal AuthenticatedUser caller, @Valid @RequestBody InviteRequest request) {
        int capacity = request.maxOpenTickets() != null ? request.maxOpenTickets() : AdminUserController.DEFAULT_MAX_OPEN_TICKETS;
        InvitationService.Issued issued = invitations.invite(caller.tenantId(), request.email(), request.role(),
                request.teamId(), capacity, caller.userId());
        return new InviteResponse(issued.invitation(), InvitationLinks.acceptUrl(frontendUrl, issued.token()));
    }

    @DeleteMapping("/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke an open invitation")
    void revoke(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID invitationId) {
        invitations.revoke(caller.tenantId(), invitationId);
    }
}
