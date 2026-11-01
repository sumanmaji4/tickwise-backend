package com.tickwise.ticket;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AuditRepository extends JpaRepository<AuditEntry, Long> {

    List<AuditEntry> findAllByTenantIdAndTicketIdOrderByIdAsc(UUID tenantId, long ticketId);
}
