package com.tickwise.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import com.tickwise.assignment.RoundRobin.Candidate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoundRobinTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private static Candidate free(UUID id) {
        return new Candidate(id, 0, 5);
    }

    private static Candidate full(UUID id) {
        return new Candidate(id, 5, 5);
    }

    @Test
    void firstTicketGoesToTheFirstAgentInStableOrder() {
        assertThat(RoundRobin.pick(List.of(free(C), free(A), free(B)), null)).contains(A);
    }

    @Test
    void rotatesAfterThePreviousAssignee() {
        List<Candidate> team = List.of(free(A), free(B), free(C));
        assertThat(RoundRobin.pick(team, A)).contains(B);
        assertThat(RoundRobin.pick(team, B)).contains(C);
        assertThat(RoundRobin.pick(team, C)).contains(A);
    }

    @Test
    void skipsAgentsAtCapacity() {
        assertThat(RoundRobin.pick(List.of(free(A), full(B), free(C)), A)).contains(C);
        assertThat(RoundRobin.pick(List.of(full(A), full(B), free(C)), C)).contains(C);
    }

    @Test
    void nobodyWithCapacityMeansNoPick() {
        assertThat(RoundRobin.pick(List.of(full(A), full(B)), null)).isEmpty();
        assertThat(RoundRobin.pick(List.of(), null)).isEmpty();
    }

    @Test
    void zeroCapacityAgentsNeverReceiveTickets() {
        assertThat(RoundRobin.pick(List.of(new Candidate(A, 0, 0), free(B)), null)).contains(B);
    }

    @Test
    void previousAssigneeWhoLeftTheTeamDoesNotResetTheRotation() {
        UUID departed = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        // B left; the next after B in id order is C, not A.
        assertThat(RoundRobin.pick(List.of(free(A), free(C)), departed)).contains(C);
    }

    @Test
    void distributesEvenlyOverManyTickets() {
        List<UUID> picks = new ArrayList<>();
        UUID last = null;
        for (int i = 0; i < 9; i++) {
            last = RoundRobin.pick(List.of(free(A), free(B), free(C)), last).orElseThrow();
            picks.add(last);
        }
        assertThat(picks).containsExactly(A, B, C, A, B, C, A, B, C);
    }
}
