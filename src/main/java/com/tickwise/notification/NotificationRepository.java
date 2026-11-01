package com.tickwise.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findAllByTenantIdAndUserIdOrderByCreatedAtDesc(UUID tenantId, UUID userId, Pageable page);

    List<Notification> findAllByTenantIdAndUserIdAndReadAtIsNullOrderByCreatedAtDesc(UUID tenantId, UUID userId, Pageable page);

    long countByTenantIdAndUserIdAndReadAtIsNull(UUID tenantId, UUID userId);

    Optional<Notification> findByTenantIdAndUserIdAndId(UUID tenantId, UUID userId, UUID id);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.tenantId = :tenantId and n.userId = :userId and n.readAt is null")
    int markAllRead(UUID tenantId, UUID userId, Instant now);
}
