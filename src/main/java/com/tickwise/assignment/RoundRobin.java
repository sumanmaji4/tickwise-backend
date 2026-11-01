package com.tickwise.assignment;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure round-robin selection over a team's agents in a stable order (by id), starting after the
 * agent who received the previous ticket and skipping anyone at capacity. If the previous agent
 * has left the team, selection resumes at the next id after theirs, so order is preserved
 * rather than restarted.
 */
final class RoundRobin {

    record Candidate(UUID agentId, long openTickets, int capacity) {
        boolean hasCapacity() {
            return openTickets < capacity;
        }
    }

    private RoundRobin() {}

    static Optional<UUID> pick(List<Candidate> candidates, UUID lastAssigned) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        // Compare as strings: lexicographic hex order is stable and independent of UUID.compareTo's signed arithmetic.
        List<Candidate> ordered = candidates.stream()
                .sorted(Comparator.comparing(c -> c.agentId().toString()))
                .toList();
        int start = 0;
        if (lastAssigned != null) {
            String last = lastAssigned.toString();
            start = ordered.size(); // wraps to 0 if every id is <= last
            for (int i = 0; i < ordered.size(); i++) {
                if (ordered.get(i).agentId().toString().compareTo(last) > 0) {
                    start = i;
                    break;
                }
            }
        }
        for (int step = 0; step < ordered.size(); step++) {
            Candidate c = ordered.get((start + step) % ordered.size());
            if (c.hasCapacity()) {
                return Optional.of(c.agentId());
            }
        }
        return Optional.empty();
    }
}
