package com.tickwise.ticket;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

final class TicketSpecifications {

    private TicketSpecifications() {}

    /** The tenant predicate is always present; filters are AND-ed onto it. */
    static Specification<Ticket> matching(UUID tenantId, TicketQuery query) {
        return (root, cq, cb) -> {
            List<jakarta.persistence.criteria.Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("tenantId"), tenantId));
            if (query.statuses() != null && !query.statuses().isEmpty()) {
                p.add(root.get("status").in(query.statuses()));
            }
            if (query.priorities() != null && !query.priorities().isEmpty()) {
                p.add(root.get("priority").in(query.priorities()));
            }
            if (query.assigneeId() != null) {
                p.add(cb.equal(root.get("assigneeId"), query.assigneeId()));
            }
            if (Boolean.TRUE.equals(query.unassigned())) {
                p.add(cb.isNull(root.get("assigneeId")));
            }
            if (query.teamId() != null) {
                p.add(cb.equal(root.get("teamId"), query.teamId()));
            }
            if (Boolean.TRUE.equals(query.breached())) {
                p.add(cb.isTrue(root.get("slaBreached")));
            }
            if (query.text() != null && !query.text().isBlank()) {
                String like = "%" + escapeLike(query.text().strip().toLowerCase()) + "%";
                p.add(cb.like(cb.lower(root.get("subject")), like, '\\'));
            }
            return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
