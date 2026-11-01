package com.tickwise.sla;

import com.tickwise.shared.domain.Priority;
import com.tickwise.shared.security.AuthenticatedUser;
import com.tickwise.sla.BusinessCalendarView.HoursView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** SLA policies and business calendar. ADMIN only via {@code /api/admin/**}. */
@RestController
@RequestMapping("/api/admin/sla")
@Tag(name = "Admin: SLA")
class AdminSlaController {

    private static final int MAX_MINUTES = 525_600; // one year

    private final SlaPolicyService policies;
    private final BusinessCalendarService calendars;

    AdminSlaController(SlaPolicyService policies, BusinessCalendarService calendars) {
        this.policies = policies;
        this.calendars = calendars;
    }

    record PolicyRequest(@Min(1) @Max(MAX_MINUTES) int firstResponseMinutes,
                         @Min(1) @Max(MAX_MINUTES) int resolutionMinutes) {}

    record CalendarRequest(@NotBlank String timezone,
                           @NotNull @Size(max = 50) List<@Valid @NotNull HoursView> hours) {}

    record HolidayRequest(@NotNull LocalDate date, @NotBlank @Size(max = 100) String name) {}

    @GetMapping("/policies")
    @Operation(summary = "SLA targets per priority, in business minutes")
    List<SlaPolicyView> policies(@AuthenticationPrincipal AuthenticatedUser caller) {
        return policies.list(caller.tenantId());
    }

    @PutMapping("/policies/{priority}")
    @Operation(summary = "Set the SLA targets for a priority (applies to new and re-prioritised tickets)")
    SlaPolicyView updatePolicy(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Priority priority,
                               @Valid @RequestBody PolicyRequest request) {
        return policies.update(caller.tenantId(), priority, request.firstResponseMinutes(), request.resolutionMinutes());
    }

    @GetMapping("/calendar")
    @Operation(summary = "Timezone, working hours and holidays")
    BusinessCalendarView calendar(@AuthenticationPrincipal AuthenticatedUser caller) {
        return calendars.view(caller.tenantId());
    }

    @PutMapping("/calendar")
    @Operation(summary = "Replace the timezone and working hours")
    BusinessCalendarView replaceCalendar(@AuthenticationPrincipal AuthenticatedUser caller,
                                         @Valid @RequestBody CalendarRequest request) {
        return calendars.replaceHours(caller.tenantId(), request.timezone(), request.hours());
    }

    @PostMapping("/calendar/holidays")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a full-day holiday")
    BusinessCalendarView.HolidayView addHoliday(@AuthenticationPrincipal AuthenticatedUser caller,
                                                @Valid @RequestBody HolidayRequest request) {
        return calendars.addHoliday(caller.tenantId(), request.date(), request.name());
    }

    @DeleteMapping("/calendar/holidays/{date}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a holiday")
    void removeHoliday(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable LocalDate date) {
        calendars.removeHoliday(caller.tenantId(), date);
    }
}
