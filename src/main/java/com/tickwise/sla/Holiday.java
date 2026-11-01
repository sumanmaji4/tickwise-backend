package com.tickwise.sla;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "holidays")
class Holiday {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private String name;

    protected Holiday() {}

    Holiday(UUID tenantId, LocalDate date, String name) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.date = date;
        this.name = name;
    }

    LocalDate date() {
        return date;
    }

    BusinessCalendarView.HolidayView toView() {
        return new BusinessCalendarView.HolidayView(date, name);
    }
}
