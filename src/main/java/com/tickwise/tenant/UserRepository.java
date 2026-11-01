package com.tickwise.tenant;

import com.tickwise.shared.security.Role;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every finder takes the tenant id; there is deliberately no unscoped lookup by id. */
interface UserRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<UserAccount> findByTenantIdAndEmail(UUID tenantId, String email);

    List<UserAccount> findAllByTenantIdOrderByEmail(UUID tenantId);

    boolean existsByTenantIdAndEmail(UUID tenantId, String email);

    List<UserAccount> findAllByTenantIdAndTeamIdAndActiveTrueAndRoleInOrderById(UUID tenantId, UUID teamId, Collection<Role> roles);

    List<UserAccount> findAllByTenantIdAndActiveTrueAndRoleIn(UUID tenantId, Collection<Role> roles);

    List<UserAccount> findAllByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);
}
