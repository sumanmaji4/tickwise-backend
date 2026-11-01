package com.tickwise.sla;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Pure, side-effect-free arithmetic over business time.
 *
 * <p>Working windows are defined in the tenant's local wall-clock time, while every input and
 * output is a UTC {@link Instant}. Each window is converted to the pair of instants at which it
 * starts and ends. Business time is the <em>real elapsed time</em> inside those intervals, so a
 * 01:00–04:00 window is two hours long on a spring-forward day and four hours on a fall-back
 * day. A local time that falls in a DST gap is shifted forward by the gap, and an ambiguous local
 * time in an overlap uses the earlier offset (the {@link ZonedDateTime#of} rules).
 *
 * <p>Overlapping intervals (for example an overnight window running into the next morning's
 * window) are de-duplicated, so no time is counted twice.
 */
public final class BusinessTimeCalculator {

    /** Upper bound on how far ahead to search, so a calendar that is all holidays fails fast instead of looping. */
    static final int MAX_SEARCH_DAYS = 366 * 5;

    private final BusinessCalendar calendar;
    private final ZoneId zone;

    public BusinessTimeCalculator(BusinessCalendar calendar) {
        this.calendar = Objects.requireNonNull(calendar, "calendar");
        this.zone = calendar.zone();
    }

    public BusinessCalendar calendar() {
        return calendar;
    }

    /**
     * The instant at which {@code amount} of business time has elapsed after {@code start}.
     * A deadline that is used up exactly at the end of a window is that window's end, not the
     * start of the next window. A zero amount returns {@code start} unchanged.
     */
    public Instant addBusinessTime(Instant start, Duration amount) {
        Objects.requireNonNull(start, "start");
        if (amount.isNegative()) {
            throw new IllegalArgumentException("amount must not be negative: " + amount);
        }
        if (amount.isZero()) {
            return start;
        }
        Instant[] result = new Instant[1];
        Duration[] remaining = {amount};
        forEachInterval(start, interval -> {
            Duration available = interval.length();
            if (remaining[0].compareTo(available) <= 0) {
                result[0] = interval.start().plus(remaining[0]);
                return false;
            }
            remaining[0] = remaining[0].minus(available);
            return true;
        });
        return result[0];
    }

    /** Business time in {@code [from, to)}; zero when {@code to} is not after {@code from}. */
    public Duration businessTimeBetween(Instant from, Instant to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!to.isAfter(from)) {
            return Duration.ZERO;
        }
        Duration[] total = {Duration.ZERO};
        forEachInterval(from, interval -> {
            if (!interval.start().isBefore(to)) {
                return false;
            }
            Instant end = interval.end().isBefore(to) ? interval.end() : to;
            total[0] = total[0].plus(Duration.between(interval.start(), end));
            return interval.end().isBefore(to);
        });
        return total[0];
    }

    /** Whether {@code instant} falls inside working time (window start inclusive, end exclusive). */
    public boolean isBusinessTime(Instant instant) {
        boolean[] inside = {false};
        forEachInterval(instant, interval -> {
            inside[0] = !interval.start().isAfter(instant);
            return false;
        });
        return inside[0];
    }

    private record Interval(Instant start, Instant end) {
        Duration length() {
            return Duration.between(start, end);
        }
    }

    /**
     * Streams disjoint business intervals in chronological order, none starting before
     * {@code from}, until the consumer returns {@code false}. An interval that overlaps an
     * earlier one is clipped to begin where the earlier one ended, so no time is counted twice.
     * Touching intervals are emitted as consecutive pieces, not merged: merging could grow
     * without bound on a 24/7 calendar. Scanning starts one day before {@code from} so that an
     * overnight window that began the previous evening is included.
     */
    private void forEachInterval(Instant from, Predicate<Interval> consumer) {
        LocalDate day = LocalDate.ofInstant(from, zone).minusDays(1);
        Instant covered = from; // everything before this instant has been emitted or is out of range
        for (int i = 0; i <= MAX_SEARCH_DAYS; i++, day = day.plusDays(1)) {
            for (Interval next : intervalsStartingOn(day)) {
                if (!next.end().isAfter(covered)) {
                    continue;
                }
                Interval piece = new Interval(later(next.start(), covered), next.end());
                covered = piece.end();
                if (!consumer.test(piece)) {
                    return;
                }
            }
        }
        throw new IllegalStateException("No business time found within " + MAX_SEARCH_DAYS + " days of " + from
                + "; check the calendar's working hours and holidays");
    }

    private List<Interval> intervalsStartingOn(LocalDate day) {
        List<WorkingWindow> windows = calendar.windowsStartingOn(day);
        List<Interval> result = new ArrayList<>(windows.size());
        for (WorkingWindow w : windows) {
            Instant start = ZonedDateTime.of(day, w.start(), zone).toInstant();
            LocalDate endDay = w.crossesMidnight() ? day.plusDays(1) : day;
            Instant end = ZonedDateTime.of(endDay, w.end(), zone).toInstant();
            if (end.isAfter(start)) { // a window lying entirely inside a DST gap has no real length
                result.add(new Interval(start, end));
            }
        }
        return result;
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
