package com.tickwise.email;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface InboundEmailRepository extends JpaRepository<InboundEmail, Long> {

    /**
     * Claims the oldest due email. {@code SKIP LOCKED} lets several workers drain the queue in
     * parallel without blocking on, or double-processing, the same row.
     */
    @Query(value = """
            select * from inbound_emails
            where status = 'PENDING' and next_attempt_at <= :now
            order by id limit 1 for update skip locked""", nativeQuery = true)
    Optional<InboundEmail> claimNext(Instant now);
}
