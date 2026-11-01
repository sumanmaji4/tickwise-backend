package com.tickwise.reporting;

import com.tickwise.shared.domain.Times;
import com.tickwise.shared.error.BadRequestException;
import com.tickwise.shared.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Manager and admin reports (see SecurityConfig). Range defaults to the last 30 days. */
@RestController
@RequestMapping("/api/reports")
@Tag(name = "Reports")
class ReportController {

    private static final Duration DEFAULT_RANGE = Duration.ofDays(30);
    private static final Duration MAX_RANGE = Duration.ofDays(366);

    private final ReportService reports;
    private final Clock clock;

    ReportController(ReportService reports, Clock clock) {
        this.reports = reports;
        this.clock = clock;
    }

    private record Range(Instant from, Instant to) {}

    @GetMapping("/summary")
    @Operation(summary = "Tenant-wide response times, breach rates and open tickets")
    ReportViews.Summary summary(@AuthenticationPrincipal AuthenticatedUser caller,
                                @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        Range r = range(from, to);
        return reports.summary(caller.tenantId(), r.from(), r.to());
    }

    @GetMapping("/teams")
    @Operation(summary = "The same metrics per team")
    List<ReportViews.GroupRow> teams(@AuthenticationPrincipal AuthenticatedUser caller,
                                     @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        Range r = range(from, to);
        return reports.byTeam(caller.tenantId(), r.from(), r.to());
    }

    @GetMapping("/agents")
    @Operation(summary = "The same metrics per agent, with current workload and capacity")
    List<ReportViews.AgentRow> agents(@AuthenticationPrincipal AuthenticatedUser caller,
                                      @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        Range r = range(from, to);
        return reports.byAgent(caller.tenantId(), r.from(), r.to());
    }

    @GetMapping("/daily")
    @Operation(summary = "Tickets created and breached per day (tenant timezone)")
    ReportViews.Daily daily(@AuthenticationPrincipal AuthenticatedUser caller,
                            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        Range r = range(from, to);
        return reports.daily(caller.tenantId(), r.from(), r.to());
    }

    private Range range(Instant from, Instant to) {
        Instant end = to != null ? to : Times.now(clock);
        Instant start = from != null ? from : end.minus(DEFAULT_RANGE);
        if (!start.isBefore(end)) {
            throw new BadRequestException("'from' must be before 'to'");
        }
        if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw new BadRequestException("The report range may not exceed 366 days");
        }
        return new Range(start, end);
    }
}
