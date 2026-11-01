package com.tickwise.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response shapes. Durations are business minutes; rates are fractions in [0, 1], or null when nothing was decided. */
public final class ReportViews {

    private ReportViews() {}

    public record Metrics(
            long created,
            long open,
            Double firstResponseAvgMinutes,
            Double firstResponseMedianMinutes,
            Double resolutionAvgMinutes,
            Double resolutionMedianMinutes,
            Double firstResponseBreachRate,
            Double resolutionBreachRate,
            Double slaBreachRate) {}

    public record Summary(Instant from, Instant to, Metrics metrics) {}

    public record GroupRow(UUID id, String name, Metrics metrics) {}

    public record AgentRow(UUID id, String name, UUID teamId, int capacity, Metrics metrics) {}

    public record DailyPoint(LocalDate date, long created, long breached) {}

    public record Daily(Instant from, Instant to, String timezone, List<DailyPoint> points) {}
}
