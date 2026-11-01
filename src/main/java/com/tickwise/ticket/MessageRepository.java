package com.tickwise.ticket;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findAllByTenantIdAndTicketIdOrderByCreatedAtAsc(UUID tenantId, long ticketId);

    Optional<Message> findByTenantIdAndId(UUID tenantId, UUID id);

    boolean existsByTenantIdAndEmailMessageId(UUID tenantId, String emailMessageId);

    @Query("select m.ticketId from Message m where m.tenantId = :tenantId and m.emailMessageId in :ids order by m.createdAt desc")
    List<Long> findTicketIdsByEmailMessageIds(UUID tenantId, Collection<String> ids);

    Optional<Message> findFirstByTenantIdAndTicketIdAndDirectionAndEmailMessageIdNotNullOrderByCreatedAtDesc(
            UUID tenantId, long ticketId, MessageDirection direction);

    @Query("select m.emailMessageId from Message m where m.tenantId = :tenantId and m.ticketId = :ticketId "
            + "and m.emailMessageId is not null order by m.createdAt asc")
    List<String> findEmailMessageIds(UUID tenantId, long ticketId);
}
