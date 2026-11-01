package com.tickwise.sla;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface TicketSlaRepository extends JpaRepository<TicketSla, UUID> {

    List<TicketSla> findAllByTenantIdAndTicketId(UUID tenantId, long ticketId);

    Optional<TicketSla> findByTenantIdAndTicketIdAndMetric(UUID tenantId, long ticketId, SlaMetric metric);

    /** Running clocks, for re-seeding the escalation queue on worker start-up. Keyset-paginated by id. */
    @Query("select s from TicketSla s where s.runningSince is not null and s.id > :after order by s.id")
    List<TicketSla> findRunningAfter(UUID after, Pageable page);
}
