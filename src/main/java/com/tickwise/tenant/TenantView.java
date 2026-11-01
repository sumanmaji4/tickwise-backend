package com.tickwise.tenant;

import java.time.ZoneId;
import java.util.UUID;

public record TenantView(UUID id, String slug, String name, ZoneId timezone) {}
