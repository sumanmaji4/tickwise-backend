package com.tickwise.sla;

import com.tickwise.shared.domain.Priority;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SlaPolicyRepository extends JpaRepository<SlaPolicy, UUID> {

    Optional<SlaPolicy> findByTenantIdAndPriority(UUID tenantId, Priority priority);

    List<SlaPolicy> findAllByTenantId(UUID tenantId);
}
