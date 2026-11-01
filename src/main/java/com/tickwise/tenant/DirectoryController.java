package com.tickwise.tenant;

import com.tickwise.shared.security.AuthenticatedUser;
import com.tickwise.shared.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only staff directory for pickers in the dashboard (assignee, team filter). Staff roles only. */
@RestController
@RequestMapping("/api/directory")
@Tag(name = "Directory")
class DirectoryController {

    private final UserService users;
    private final TeamService teams;

    DirectoryController(UserService users, TeamService teams) {
        this.users = users;
        this.teams = teams;
    }

    record Person(UUID id, String displayName, Role role, UUID teamId) {}

    @GetMapping("/agents")
    @Operation(summary = "Active agents and managers")
    List<Person> agents(@AuthenticationPrincipal AuthenticatedUser caller) {
        return users.activeWithRoles(caller.tenantId(), Set.of(Role.AGENT, Role.MANAGER)).stream()
                .map(u -> new Person(u.id(), u.displayName(), u.role(), u.teamId()))
                .sorted(java.util.Comparator.comparing(Person::displayName))
                .toList();
    }

    @GetMapping("/teams")
    @Operation(summary = "Teams")
    List<TeamView> teams(@AuthenticationPrincipal AuthenticatedUser caller) {
        return teams.list(caller.tenantId());
    }
}
