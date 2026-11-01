package com.tickwise.tenant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    Optional<Invitation> findByTokenHash(String tokenHash);

    Optional<Invitation> findByTenantIdAndId(UUID tenantId, UUID id);

    @Query("""
            select i from Invitation i
            where i.tenantId = :tenantId and i.acceptedAt is null and i.revokedAt is null
            order by i.createdAt desc""")
    List<Invitation> findPending(UUID tenantId);

    @Query("""
            select i from Invitation i
            where i.tenantId = :tenantId and i.email = :email and i.acceptedAt is null and i.revokedAt is null""")
    Optional<Invitation> findPendingFor(UUID tenantId, String email);
}
