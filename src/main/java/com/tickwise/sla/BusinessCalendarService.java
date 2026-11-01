package com.tickwise.sla;

import com.tickwise.shared.error.BadRequestException;
import com.tickwise.shared.error.ConflictException;
import com.tickwise.shared.error.NotFoundException;


import com.tickwise.tenant.TenantService;
import com.tickwise.tenant.TenantView;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads and edits a tenant's working hours and holidays, and builds its {@link BusinessTimeCalculator}. */
@Service
public class BusinessCalendarService {

    static final WorkingWindow DEFAULT_HOURS = WorkingWindow.of("09:00", "17:00");

    private final BusinessHoursRepository hours;
    private final HolidayRepository holidays;
    private final TenantService tenants;

    BusinessCalendarService(BusinessHoursRepository hours, HolidayRepository holidays, TenantService tenants) {
        this.hours = hours;
        this.holidays = holidays;
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public BusinessTimeCalculator calculatorFor(UUID tenantId) {
        return new BusinessTimeCalculator(calendarFor(tenantId));
    }

    @Transactional(readOnly = true)
    public BusinessCalendar calendarFor(UUID tenantId) {
        TenantView tenant = tenants.get(tenantId);
        Map<DayOfWeek, List<WorkingWindow>> windows = hours.findAllByTenantId(tenantId).stream()
                .collect(Collectors.groupingBy(BusinessHours::day,
                        () -> new EnumMap<>(DayOfWeek.class),
                        Collectors.mapping(BusinessHours::window, Collectors.toList())));
        var holidayDates = holidays.findAllByTenantIdOrderByDate(tenantId).stream().map(Holiday::date)
                .collect(Collectors.toSet());
        return new BusinessCalendar(tenant.timezone(), windows, holidayDates);
    }

    @Transactional(readOnly = true)
    public BusinessCalendarView view(UUID tenantId) {
        TenantView tenant = tenants.get(tenantId);
        List<BusinessCalendarView.HoursView> hourViews = hours.findAllByTenantId(tenantId).stream()
                .map(h -> new BusinessCalendarView.HoursView(h.day(), h.window().start(), h.window().end()))
                .sorted(Comparator.comparing(BusinessCalendarView.HoursView::dayOfWeek)
                        .thenComparing(BusinessCalendarView.HoursView::start))
                .toList();
        List<BusinessCalendarView.HolidayView> holidayViews =
                holidays.findAllByTenantIdOrderByDate(tenantId).stream().map(Holiday::toView).toList();
        return new BusinessCalendarView(tenant.timezone().getId(), hourViews, holidayViews);
    }

    /**
     * Replaces the timezone and all working windows. The new calendar is validated as a whole
     * before anything is written. As with policies, running SLA clocks pick up the change at
     * their next transition rather than being recomputed retroactively.
     */
    @Transactional
    public BusinessCalendarView replaceHours(UUID tenantId, String timezone, List<BusinessCalendarView.HoursView> input) {
        Map<DayOfWeek, List<WorkingWindow>> windows = new EnumMap<>(DayOfWeek.class);
        for (BusinessCalendarView.HoursView h : input) {
            windows.computeIfAbsent(h.dayOfWeek(), d -> new ArrayList<>()).add(toWindow(h));
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
            new BusinessCalendar(zone, windows, java.util.Set.of());
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            throw new BadRequestException(e.getMessage());
        }
        tenants.changeTimezone(tenantId, zone.getId());
        hours.deleteAllForTenant(tenantId);
        hours.flush();
        windows.forEach((day, list) -> list.forEach(w -> hours.save(new BusinessHours(tenantId, day, w))));
        return view(tenantId);
    }

    @Transactional
    public BusinessCalendarView.HolidayView addHoliday(UUID tenantId, LocalDate date, String name) {
        if (holidays.findByTenantIdAndDate(tenantId, date).isPresent()) {
            throw new ConflictException("A holiday on " + date + " already exists");
        }
        return holidays.save(new Holiday(tenantId, date, name.strip())).toView();
    }

    @Transactional
    public void removeHoliday(UUID tenantId, LocalDate date) {
        Holiday holiday = holidays.findByTenantIdAndDate(tenantId, date)
                .orElseThrow(() -> new NotFoundException("Holiday", date));
        holidays.delete(holiday);
    }

    @Transactional
    void provisionDefaults(UUID tenantId) {
        if (!hours.findAllByTenantId(tenantId).isEmpty()) {
            return;
        }
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                hours.save(new BusinessHours(tenantId, day, DEFAULT_HOURS));
            }
        }
    }

    private static WorkingWindow toWindow(BusinessCalendarView.HoursView h) {
        try {
            return new WorkingWindow(h.start(), h.end());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Invalid working window for " + h.dayOfWeek() + ": " + e.getMessage());
        }
    }
}
