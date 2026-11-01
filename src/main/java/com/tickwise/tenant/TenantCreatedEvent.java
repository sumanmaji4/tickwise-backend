package com.tickwise.tenant;

import java.util.UUID;

/** Published inside the creating transaction so other modules can provision per-tenant defaults atomically. */
public record TenantCreatedEvent(UUID tenantId) {}
