package com.tickwise.sla;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A tenant's working time: a timezone, working windows per weekday and full-day holidays.
 * Immutable and validated on construction.
 *
 * <p>A holiday removes every window that <em>starts</em> on that date, including an overnight
 * window that would have run into the next day.
 */
public final class BusinessCalendar {

    private final ZoneId zone;
    private final Map<DayOfWeek, List<WorkingWindow>> windows;
    private final Set<LocalDate> holidays;

    public BusinessCalendar(ZoneId zone, Map<DayOfWeek, List<WorkingWindow>> windows, Set<LocalDate> holidays) {
        this.zone = Objects.requireNonNull(zone, "zone");
        this.holidays = Set.copyOf(holidays);
        EnumMap<DayOfWeek, List<WorkingWindow>> copy = new EnumMap<>(DayOfWeek.class);
        windows.forEach((day, list) -> {
            if (!list.isEmpty()) {
                copy.put(day, validated(day, list));
            }
        });
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("A business calendar needs at least one working window");
        }
        this.windows = copy;
    }

    /** Monday–Friday with the same hours each day. */
    public static BusinessCalendar weekdays(ZoneId zone, WorkingWindow hours, Set<LocalDate> holidays) {
        EnumMap<DayOfWeek, List<WorkingWindow>> map = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY) {
                map.put(d, List.of(hours));
            }
        }
        return new BusinessCalendar(zone, map, holidays);
    }

    public ZoneId zone() {
        return zone;
    }

    public Set<LocalDate> holidays() {
        return holidays;
    }

    public Map<DayOfWeek, List<WorkingWindow>> windows() {
        return windows;
    }

    /** Windows that start on {@code date}, ordered by start time; empty on holidays. */
    List<WorkingWindow> windowsStartingOn(LocalDate date) {
        if (holidays.contains(date)) {
            return List.of();
        }
        return windows.getOrDefault(date.getDayOfWeek(), List.of());
    }

    private static List<WorkingWindow> validated(DayOfWeek day, List<WorkingWindow> input) {
        List<WorkingWindow> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparing(WorkingWindow::start));
        for (int i = 0; i < sorted.size(); i++) {
            WorkingWindow w = sorted.get(i);
            boolean last = i == sorted.size() - 1;
            if (w.crossesMidnight() && !last) {
                throw new IllegalArgumentException(day + ": only the last window of a day may run past midnight");
            }
            if (!last && sorted.get(i + 1).start().isBefore(w.end())) {
                throw new IllegalArgumentException(day + ": working windows " + w + " and " + sorted.get(i + 1) + " overlap");
            }
        }
        return List.copyOf(sorted);
    }

    @Override
    public String toString() {
        return "BusinessCalendar[zone=" + zone + ", windows=" + windows + ", holidays=" + holidays.size() + "]";
    }
}
