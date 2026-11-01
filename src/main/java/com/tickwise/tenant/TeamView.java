package com.tickwise.tenant;

import java.time.Instant;
import java.util.UUID;

public record TeamView(UUID id, String name, Instant createdAt) {}
