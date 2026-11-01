package com.tickwise.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import com.tickwise.shared.error.ConflictException;
import com.tickwise.shared.security.Role;
import com.tickwise.support.IntegrationTest;
import com.tickwise.tenant.TeamView;
import com.tickwise.tenant.UserView;
import com.tickwise.ticket.TicketService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Proves the guarantees of ADR 0004 with real concurrent transactions against Postgres:
 * a ticket can never end up claimed by two agents, and capacity limits hold under contention.
 */
class AssignmentConcurrencyIT extends IntegrationTest {

    private static final int THREADS = 8;

    @Autowired AssignmentService assignments;
    @Autowired TicketService tickets;

    private enum Outcome { WON, LOST }

    @Test
    void exactlyOneOfManySimultaneousClaimsWins() throws Exception {
        Fixture f = newTenant("race");
        // Agents spread over two teams, so claims contend on the ticket row itself (optimistic
        // locking), not only on a shared team lock.
        TeamView second = teamService.create(f.tenant().id(), "Second line");
        List<UserView> agents = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            agents.add(newUser(f.tenant(), "racer" + i + "@" + f.tenant().slug() + ".test", Role.AGENT,
                    i % 2 == 0 ? f.team().id() : second.id(), 10));
        }
        long ticketId = submitTicket(f, "pat@customer.test", "Contended");

        List<Outcome> outcomes = runConcurrently(agents.stream()
                .<Callable<Outcome>>map(agent -> () -> claim(f.tenant().id(), ticketId, agent.id()))
                .toList());

        assertThat(outcomes).filteredOn(o -> o == Outcome.WON).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o == Outcome.LOST).hasSize(THREADS - 1);
        UUID owner = tickets.get(f.tenant().id(), ticketId).assigneeId();
        assertThat(agents).extracting(UserView::id).contains(owner);
        assertThat(jdbc.queryForObject("select count(*) from ticket_audit where ticket_id = ? and type = 'ASSIGNED'",
                Integer.class, ticketId)).isEqualTo(1);
    }

    @Test
    void sameTeamClaimsAreSerialisedAndStillProduceOneOwner() throws Exception {
        Fixture f = newTenant("race-team");
        List<UserView> agents = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            agents.add(newUser(f.tenant(), "teammate" + i + "@" + f.tenant().slug() + ".test", Role.AGENT, f.team().id(), 10));
        }
        long ticketId = submitTicket(f, "pat@customer.test", "Contended in one team");

        List<Outcome> outcomes = runConcurrently(agents.stream()
                .<Callable<Outcome>>map(agent -> () -> claim(f.tenant().id(), ticketId, agent.id()))
                .toList());

        assertThat(outcomes).filteredOn(o -> o == Outcome.WON).hasSize(1);
    }

    @Test
    void concurrentAutoAssignmentNeverExceedsCapacity() throws Exception {
        Fixture f = newTenant("capacity");
        TeamView team = teamService.create(f.tenant().id(), "Tier 1");
        List<UserView> agents = List.of(
                newUser(f.tenant(), "a@" + f.tenant().slug() + ".test", Role.AGENT, team.id(), 2),
                newUser(f.tenant(), "b@" + f.tenant().slug() + ".test", Role.AGENT, team.id(), 2),
                newUser(f.tenant(), "c@" + f.tenant().slug() + ".test", Role.AGENT, team.id(), 2));
        List<Long> ticketIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ticketIds.add(submitTicket(f, "customer" + i + "@customer.test", "Load " + i));
        }
        // Enable routing only now, so creation itself did not assign anything.
        assignments.updateSettings(f.tenant().id(), team.id(), true);

        runConcurrently(ticketIds.stream()
                .<Callable<Outcome>>map(id -> () -> assignments.autoAssign(f.tenant().id(), id).isPresent()
                        ? Outcome.WON : Outcome.LOST)
                .toList());

        Map<UUID, Long> open = tickets.openTicketCounts(f.tenant().id(), agents.stream().map(UserView::id).toList());
        assertThat(open.values()).allSatisfy(n -> assertThat(n).isEqualTo(2L));
        assertThat(jdbc.queryForObject("select count(*) from tickets where tenant_id = ? and assignee_id is null",
                Integer.class, f.tenant().id())).isEqualTo(4);
    }

    @Test
    void roundRobinRotatesThroughTheTeamInStableOrder() {
        Fixture f = newTenant("rotation");
        TeamView team = teamService.create(f.tenant().id(), "Rotation");
        List<UUID> agentIds = new ArrayList<>(List.of(
                newUser(f.tenant(), "x@" + f.tenant().slug() + ".test", Role.AGENT, team.id(), 5).id(),
                newUser(f.tenant(), "y@" + f.tenant().slug() + ".test", Role.AGENT, team.id(), 5).id(),
                newUser(f.tenant(), "z@" + f.tenant().slug() + ".test", Role.AGENT, team.id(), 5).id()));
        agentIds.sort(java.util.Comparator.comparing(UUID::toString));
        assignments.updateSettings(f.tenant().id(), team.id(), true);

        List<UUID> picks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            long id = submitTicket(f, "c" + i + "@customer.test", "Rotate " + i); // auto-assigned after commit
            picks.add(tickets.get(f.tenant().id(), id).assigneeId());
        }

        assertThat(picks).containsExactly(agentIds.get(0), agentIds.get(1), agentIds.get(2),
                agentIds.get(0), agentIds.get(1), agentIds.get(2));
    }

    @Test
    void claimingBeyondCapacityIsRefused() {
        Fixture f = newTenant("full");
        UserView solo = newUser(f.tenant(), "solo@" + f.tenant().slug() + ".test", Role.AGENT, f.team().id(), 1);
        long first = submitTicket(f, "a@customer.test", "First");
        long second = submitTicket(f, "b@customer.test", "Second");

        assertThat(claim(f.tenant().id(), first, solo.id())).isEqualTo(Outcome.WON);
        assertThat(claim(f.tenant().id(), second, solo.id())).isEqualTo(Outcome.LOST);
        assertThat(tickets.get(f.tenant().id(), second).assigneeId()).isNull();
    }

    private Outcome claim(UUID tenantId, long ticketId, UUID agentId) {
        try {
            assignments.claim(tenantId, ticketId, agentId, null);
            return Outcome.WON;
        } catch (ConflictException | OptimisticLockingFailureException e) {
            return Outcome.LOST;
        }
    }

    /** Starts every task at the same instant to maximise contention. */
    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
