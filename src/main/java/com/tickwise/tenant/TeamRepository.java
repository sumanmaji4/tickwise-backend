package com.tickwise.tenant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every finder takes the tenant id; there is deliberately no unscoped lookup by id. */
interface TeamRepository extends JpaRepository<Team, UUID> {

    Optional<Team> findByTenantIdAndId(UUID tenantId, UUID id);

    List<Team> findAllByTenantIdOrderByName(UUID tenantId);

    boolean existsByTenantIdAndName(UUID tenantId, String name);
}
