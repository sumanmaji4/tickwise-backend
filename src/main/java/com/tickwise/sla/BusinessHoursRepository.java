package com.tickwise.sla;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface BusinessHoursRepository extends JpaRepository<BusinessHours, UUID> {

    List<BusinessHours> findAllByTenantId(UUID tenantId);

    @Modifying
    @Query("delete from BusinessHours h where h.tenantId = :tenantId")
    void deleteAllForTenant(UUID tenantId);
}
