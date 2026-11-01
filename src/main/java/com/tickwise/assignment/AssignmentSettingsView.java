package com.tickwise.assignment;

import java.util.UUID;

/** {@code defaultTeamId}: the team new tickets are routed to; {@code autoAssign}: round-robin on arrival. */
public record AssignmentSettingsView(UUID defaultTeamId, boolean autoAssign) {}
