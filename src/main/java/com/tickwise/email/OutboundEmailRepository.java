package com.tickwise.email;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface OutboundEmailRepository extends JpaRepository<OutboundEmail, Long> {

    @Query(value = """
            select * from outbound_emails
            where status = 'PENDING' and next_attempt_at <= :now
            order by id limit 1 for update skip locked""", nativeQuery = true)
    Optional<OutboundEmail> claimNext(Instant now);

    List<OutboundEmail> findAllByTenantIdAndTicketIdOrderByIdAsc(UUID tenantId, long ticketId);
}
