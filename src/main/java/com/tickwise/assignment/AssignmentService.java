package com.tickwise.assignment;

import com.tickwise.shared.error.ConflictException;
import com.tickwise.shared.error.NotFoundException;
import com.tickwise.shared.security.Role;
import com.tickwise.tenant.TeamService;
import com.tickwise.tenant.UserService;
import com.tickwise.tenant.UserView;
import com.tickwise.ticket.TicketService;
import com.tickwise.ticket.TicketView;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who owns a ticket. Two mechanisms make it safe under concurrency (see ADR 0004):
 *
 * <ul>
 *   <li>The ticket row is updated with optimistic locking ({@code @Version}): of two concurrent
 *       claims on the same ticket, exactly one commits and the other gets 409.</li>
 *   <li>Every assignment into a team first locks that team's cursor row
 *       ({@code SELECT ... FOR UPDATE}). That serialises the "count open tickets, then assign"
 *       sequence per team, so capacity limits hold and round-robin turns are not skipped.</li>
 * </ul>
 */
@Service
public class AssignmentService {

    private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);
    private static final Set<Role> ASSIGNABLE = Set.of(Role.AGENT, Role.MANAGER);

    private final TicketService tickets;
    private final UserService users;
    private final TeamService teams;
    private final TeamCursors cursors;
    private final AssignmentSettingsRepository settings;

    AssignmentService(TicketService tickets, UserService users, TeamService teams, TeamCursors cursors,
                      AssignmentSettingsRepository settings) {
        this.tickets = tickets;
        this.users = users;
        this.teams = teams;
        this.cursors = cursors;
        this.settings = settings;
    }

    /** An agent takes an unassigned ticket. */
    @Transactional
    public TicketView claim(UUID tenantId, long ticketId, UUID agentId, Long expectedVersion) {
        UserView agent = eligible(tenantId, agentId);
        cursors.lock(tenantId, agent.teamId());
        requireCapacity(tenantId, agent);
        return tickets.assign(tenantId, ticketId, agent.id(), agent.teamId(), expectedVersion, true, agentId);
    }

    /** A manager or admin assigns (or re-assigns) a ticket to a specific agent. */
    @Transactional
    public TicketView assignTo(UUID tenantId, long ticketId, UUID assigneeId, Long expectedVersion, UUID actorId) {
        UserView agent = eligible(tenantId, assigneeId);
        cursors.lock(tenantId, agent.teamId());
        TicketView current = tickets.get(tenantId, ticketId);
        if (!agent.id().equals(current.assigneeId())) {
            requireCapacity(tenantId, agent);
        }
        return tickets.assign(tenantId, ticketId, agent.id(), agent.teamId(), expectedVersion, false, actorId);
    }

    @Transactional
    public TicketView unassign(UUID tenantId, long ticketId, Long expectedVersion, UUID actorId) {
        return tickets.assign(tenantId, ticketId, null, null, expectedVersion, false, actorId);
    }

    /**
     * Round-robin within the ticket's team (or the tenant's default team), skipping agents at
     * capacity. Leaves the ticket unassigned in the team queue when everyone is full.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> autoAssign(UUID tenantId, long ticketId) {
        AssignmentSettingsView config = settings(tenantId);
        TicketView ticket = tickets.get(tenantId, ticketId);
        UUID teamId = ticket.teamId() != null ? ticket.teamId() : config.defaultTeamId();
        if (!config.autoAssign() || teamId == null || ticket.assigneeId() != null || !ticket.status().isActive()) {
            return Optional.empty();
        }
        UUID last = cursors.lock(tenantId, teamId);
        List<UserView> agents = users.activeTeamMembers(tenantId, teamId, Set.of(Role.AGENT));
        Map<UUID, Long> load = tickets.openTicketCounts(tenantId, agents.stream().map(UserView::id).toList());
        List<RoundRobin.Candidate> candidates = agents.stream()
                .map(a -> new RoundRobin.Candidate(a.id(), load.getOrDefault(a.id(), 0L), a.maxOpenTickets()))
                .toList();
        Optional<UUID> pick = RoundRobin.pick(candidates, last);
        if (pick.isEmpty()) {
            if (!teamId.equals(ticket.teamId())) {
                tickets.assign(tenantId, ticketId, null, teamId, null, false, null);
            }
            log.info("Ticket {} left unassigned in team {}: no agent has capacity", ticketId, teamId);
            return Optional.empty();
        }
        tickets.assign(tenantId, ticketId, pick.get(), teamId, null, true, null);
        cursors.advance(tenantId, teamId, pick.get());
        return pick;
    }

    @Transactional(readOnly = true)
    public AssignmentSettingsView settings(UUID tenantId) {
        return settings.findById(tenantId).map(AssignmentSettings::toView)
                .orElse(new AssignmentSettingsView(null, true));
    }

    @Transactional
    public AssignmentSettingsView updateSettings(UUID tenantId, UUID defaultTeamId, boolean autoAssign) {
        if (defaultTeamId != null) {
            teams.get(tenantId, defaultTeamId);
        }
        AssignmentSettings entity = settings.findById(tenantId)
                .orElseGet(() -> settings.save(new AssignmentSettings(tenantId)));
        entity.update(defaultTeamId, autoAssign);
        return entity.toView();
    }

    private UserView eligible(UUID tenantId, UUID userId) {
        UserView user = users.find(tenantId, userId).orElseThrow(() -> new NotFoundException("User", userId));
        if (!user.active() || !ASSIGNABLE.contains(user.role())) {
            throw new ConflictException(user.displayName() + " cannot be assigned tickets");
        }
        if (user.teamId() == null) {
            throw new ConflictException(user.displayName() + " must belong to a team to be assigned tickets");
        }
        return user;
    }

    private void requireCapacity(UUID tenantId, UserView agent) {
        long open = tickets.openTicketCounts(tenantId, List.of(agent.id())).getOrDefault(agent.id(), 0L);
        if (open >= agent.maxOpenTickets()) {
            throw new ConflictException(agent.displayName() + " is at capacity (" + open + "/" + agent.maxOpenTickets()
                    + " open tickets)");
        }
    }
}
