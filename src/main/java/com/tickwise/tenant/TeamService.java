package com.tickwise.tenant;

import com.tickwise.shared.error.ConflictException;
import com.tickwise.shared.error.NotFoundException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamService {

    private final TeamRepository teams;
    private final Clock clock;

    TeamService(TeamRepository teams, Clock clock) {
        this.teams = teams;
        this.clock = clock;
    }

    @Transactional
    public TeamView create(UUID tenantId, String name) {
        String trimmed = name.strip();
        if (teams.existsByTenantIdAndName(tenantId, trimmed)) {
            throw new ConflictException("A team named '" + trimmed + "' already exists");
        }
        return teams.save(new Team(UUID.randomUUID(), tenantId, trimmed, clock.instant())).toView();
    }

    @Transactional(readOnly = true)
    public List<TeamView> list(UUID tenantId) {
        return teams.findAllByTenantIdOrderByName(tenantId).stream().map(Team::toView).toList();
    }

    @Transactional(readOnly = true)
    public TeamView get(UUID tenantId, UUID teamId) {
        return teams.findByTenantIdAndId(tenantId, teamId).map(Team::toView)
                .orElseThrow(() -> new NotFoundException("Team", teamId));
    }
}
