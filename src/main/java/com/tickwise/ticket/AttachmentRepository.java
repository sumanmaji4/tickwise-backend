package com.tickwise.ticket;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    List<Attachment> findAllByTenantIdAndMessageIdIn(UUID tenantId, Collection<UUID> messageIds);

    Optional<Attachment> findByTenantIdAndId(UUID tenantId, UUID id);
}
