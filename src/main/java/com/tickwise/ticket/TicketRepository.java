package com.tickwise.ticket;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Every finder takes the tenant id. Searches go through {@link TicketSpecifications}, which always add it. */
interface TicketRepository extends JpaRepository<Ticket, Long>, JpaSpecificationExecutor<Ticket> {

    Optional<Ticket> findByTenantIdAndId(UUID tenantId, Long id);

    interface AssigneeLoad {
        UUID getAssigneeId();

        long getOpenCount();
    }

    @Query("""
            select t.assigneeId as assigneeId, count(t) as openCount from Ticket t
            where t.tenantId = :tenantId and t.assigneeId in :assigneeIds and t.status in :active
            group by t.assigneeId""")
    List<AssigneeLoad> countByAssignee(UUID tenantId, Collection<UUID> assigneeIds, Collection<TicketStatus> active);

    /** Sets the flag without bumping the version, so a background breach never conflicts with an agent's edit. */
    @Modifying
    @Query("update Ticket t set t.slaBreached = true where t.tenantId = :tenantId and t.id = :ticketId")
    int markBreached(UUID tenantId, long ticketId);
}
