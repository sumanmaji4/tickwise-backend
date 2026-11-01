package com.tickwise.sla;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "business_hours")
class BusinessHours {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    protected BusinessHours() {}

    BusinessHours(UUID tenantId, DayOfWeek day, WorkingWindow window) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.dayOfWeek = day.getValue();
        this.startTime = window.start();
        this.endTime = window.end();
    }

    DayOfWeek day() {
        return DayOfWeek.of(dayOfWeek);
    }

    WorkingWindow window() {
        return new WorkingWindow(startTime, endTime);
    }
}
