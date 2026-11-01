package com.tickwise.sla;

import com.tickwise.tenant.TenantCreatedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** New tenants get default policies and Mon–Fri 09:00–17:00 hours in the same transaction. */
@Component
class SlaDefaultsProvisioner {

    private final SlaPolicyService policies;
    private final BusinessCalendarService calendars;

    SlaDefaultsProvisioner(SlaPolicyService policies, BusinessCalendarService calendars) {
        this.policies = policies;
        this.calendars = calendars;
    }

    @EventListener
    void onTenantCreated(TenantCreatedEvent event) {
        policies.provisionDefaults(event.tenantId());
        calendars.provisionDefaults(event.tenantId());
    }
}
