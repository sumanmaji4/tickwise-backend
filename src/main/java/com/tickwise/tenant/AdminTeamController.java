package com.tickwise.tenant;

import com.tickwise.shared.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

/** Team administration. Access is restricted to ADMIN by the security filter chain ({@code /api/admin/**}). */
@RestController
@RequestMapping("/api/admin/teams")
@Tag(name = "Admin: teams")
class AdminTeamController {

    private final TeamService teams;

    AdminTeamController(TeamService teams) {
        this.teams = teams;
    }

    record CreateTeamRequest(@NotBlank @Size(max = 100) String name) {}

    @GetMapping
    @Operation(summary = "List the tenant's teams")
    List<TeamView> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return teams.list(caller.tenantId());
    }

    @GetMapping("/{teamId}")
    @Operation(summary = "Get a team")
    TeamView get(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID teamId) {
        return teams.get(caller.tenantId(), teamId);
    }

    @PostMapping
    @Operation(summary = "Create a team")
    ResponseEntity<TeamView> create(@AuthenticationPrincipal AuthenticatedUser caller,
                                    @Valid @RequestBody CreateTeamRequest request) {
        TeamView team = teams.create(caller.tenantId(), request.name());
        return ResponseEntity.created(URI.create("/api/admin/teams/" + team.id())).body(team);
    }
}
