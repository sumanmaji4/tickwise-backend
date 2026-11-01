package com.tickwise.sla;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Objects;

/**
 * A span of working wall-clock time that starts on a given weekday.
 *
 * <p>If {@code end} is not after {@code start}, the window runs past midnight into the next day:
 * {@code 22:00–06:00} is an overnight shift, {@code 09:00–00:00} runs to the end of the day and
 * {@code 00:00–00:00} is the whole day.
 */
public record WorkingWindow(LocalTime start, LocalTime end) {

    public WorkingWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (start.getNano() != 0 || end.getNano() != 0 || start.getSecond() != 0 || end.getSecond() != 0) {
            throw new IllegalArgumentException("Working windows have minute precision");
        }
    }

    public static WorkingWindow of(String start, String end) {
        return new WorkingWindow(LocalTime.parse(start), LocalTime.parse(end));
    }

    public boolean crossesMidnight() {
        return !end.isAfter(start);
    }

    /** Nominal length in wall-clock time; the real length differs on DST transition days. */
    public Duration nominalLength() {
        Duration d = Duration.between(start, end);
        return crossesMidnight() ? d.plusDays(1) : d;
    }
}
