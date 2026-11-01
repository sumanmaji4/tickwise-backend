package com.tickwise.sla;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record BusinessCalendarView(String timezone, List<HoursView> hours, List<HolidayView> holidays) {

    public record HoursView(DayOfWeek dayOfWeek, LocalTime start, LocalTime end) {}

    public record HolidayView(LocalDate date, String name) {}
}
