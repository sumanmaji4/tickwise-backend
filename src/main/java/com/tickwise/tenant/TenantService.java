package com.tickwise.tenant;

import com.tickwise.shared.error.BadRequestException;
import com.tickwise.shared.error.ConflictException;
import com.tickwise.shared.error.NotFoundException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant lookup and provisioning. Provisioning is a platform operation with no tenant-facing endpoint. */
@Service
public class TenantService {

    private static final Pattern SLUG = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    private final TenantRepository tenants;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    TenantService(TenantRepository tenants, ApplicationEventPublisher events, Clock clock) {
        this.tenants = tenants;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public TenantView create(String slug, String name, String timezone) {
        if (!SLUG.matcher(slug).matches()) {
            throw new BadRequestException("Tenant slug must be lowercase letters, digits and inner hyphens");
        }
        ZoneId zone = parseZone(timezone);
        if (tenants.existsBySlug(slug)) {
            throw new ConflictException("Tenant slug '" + slug + "' is taken");
        }
        Tenant tenant = tenants.save(new Tenant(UUID.randomUUID(), slug, name, zone, clock.instant()));
        events.publishEvent(new TenantCreatedEvent(tenant.getId()));
        return tenant.toView();
    }

    @Transactional
    public TenantView changeTimezone(UUID tenantId, String timezone) {
        Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant", tenantId));
        tenant.changeTimezone(parseZone(timezone));
        return tenant.toView();
    }

    @Transactional(readOnly = true)
    public TenantView get(UUID tenantId) {
        return tenants.findById(tenantId).map(Tenant::toView)
                .orElseThrow(() -> new NotFoundException("Tenant", tenantId));
    }

    @Transactional(readOnly = true)
    public Optional<TenantView> findBySlug(String slug) {
        return tenants.findBySlug(slug).map(Tenant::toView);
    }

    private static ZoneId parseZone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new BadRequestException("Unknown timezone: " + timezone);
        }
    }
}
