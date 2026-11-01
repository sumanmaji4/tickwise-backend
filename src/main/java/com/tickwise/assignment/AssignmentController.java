package com.tickwise.assignment;

import com.tickwise.shared.security.AuthenticatedUser;
import com.tickwise.ticket.TicketView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Assignment")
class AssignmentController {

    private final AssignmentService assignments;

    AssignmentController(AssignmentService assignments) {
        this.assignments = assignments;
    }

    record ClaimRequest(Long version) {}

    record AssignRequest(@NotNull UUID assigneeId, Long version) {}

    record SettingsRequest(UUID defaultTeamId, boolean autoAssign) {}

    @PostMapping("/api/tickets/{ticketId}/claim")
    @Operation(summary = "Take an unassigned ticket; 409 if someone else already has it or you are at capacity")
    TicketView claim(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable long ticketId,
                     @RequestBody(required = false) ClaimRequest request) {
        return assignments.claim(caller.tenantId(), ticketId, caller.userId(), request == null ? null : request.version());
    }

    @PutMapping("/api/tickets/{ticketId}/assignee")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    @Operation(summary = "Assign or re-assign a ticket (managers and admins)")
    TicketView assign(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable long ticketId,
                      @Valid @RequestBody AssignRequest request) {
        return assignments.assignTo(caller.tenantId(), ticketId, request.assigneeId(), request.version(), caller.userId());
    }

    @DeleteMapping("/api/tickets/{ticketId}/assignee")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    @Operation(summary = "Return a ticket to the queue (managers and admins)")
    TicketView unassign(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable long ticketId,
                        @RequestParam(required = false) Long version) {
        return assignments.unassign(caller.tenantId(), ticketId, version, caller.userId());
    }

    @GetMapping("/api/admin/assignment-settings")
    @Operation(summary = "Default team and auto-assignment")
    AssignmentSettingsView settings(@AuthenticationPrincipal AuthenticatedUser caller) {
        return assignments.settings(caller.tenantId());
    }

    @PutMapping("/api/admin/assignment-settings")
    @Operation(summary = "Update default team and auto-assignment")
    AssignmentSettingsView updateSettings(@AuthenticationPrincipal AuthenticatedUser caller,
                                          @RequestBody SettingsRequest request) {
        return assignments.updateSettings(caller.tenantId(), request.defaultTeamId(), request.autoAssign());
    }
}
