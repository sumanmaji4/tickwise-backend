package com.tickwise.sla;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface HolidayRepository extends JpaRepository<Holiday, UUID> {

    List<Holiday> findAllByTenantIdOrderByDate(UUID tenantId);

    Optional<Holiday> findByTenantIdAndDate(UUID tenantId, LocalDate date);
}
