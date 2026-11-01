package com.tickwise.ticket;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByTenantIdAndEmail(UUID tenantId, String email);

    Optional<Customer> findByTenantIdAndId(UUID tenantId, UUID id);

    List<Customer> findAllByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);
}
