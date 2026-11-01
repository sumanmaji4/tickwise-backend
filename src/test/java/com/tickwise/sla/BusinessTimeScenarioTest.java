package com.tickwise.sla;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Table-driven scenarios on realistic calendars. Every expected value is written in the
 * calendar's local wall-clock time and was worked out by hand from the 2026 calendar
 * (1 Jan 2026 is a Thursday). Complements {@link BusinessTimeCalculatorTest}, which covers the
 * individual rules, and its randomised invariants.
 */
class BusinessTimeScenarioTest {

    private static Instant at(ZoneId zone, String local) {
        return ZonedDateTime.of(LocalDateTime.parse(local), zone).toInstant();
    }

    private static Map<DayOfWeek, List<WorkingWindow>> days(List<WorkingWindow> windows, DayOfWeek... days) {
        EnumMap<DayOfWeek, List<WorkingWindow>> map = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek d : days) {
            map.put(d, windows);
        }
        return map;
    }

    private static final DayOfWeek[] WEEKDAYS = {DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY};

    /**
     * London, Mon–Fri 09:00–17:00, with 2026 bank holidays: Good Friday 3 Apr, Easter Monday
     * 6 Apr, Early May 4 May, Christmas Fri 25 Dec and the Boxing Day substitute Mon 28 Dec.
     * (Spring bank holiday 25 May is deliberately absent, so it counts as a working day.)
     */
    @Nested
    class LondonBankHolidays {

        private final ZoneId london = ZoneId.of("Europe/London");
        private final BusinessTimeCalculator calc = new BusinessTimeCalculator(BusinessCalendar.weekdays(london,
                WorkingWindow.of("09:00", "17:00"),
                Set.of(LocalDate.parse("2026-04-03"), LocalDate.parse("2026-04-06"), LocalDate.parse("2026-05-04"),
                        LocalDate.parse("2026-12-25"), LocalDate.parse("2026-12-28"))));

        @ParameterizedTest(name = "{0} + {1}min -> {2}")
        @CsvSource({
                // Easter: Thursday afternoon, then Good Friday, weekend, Easter Monday
                "2026-04-02T16:00, 120, 2026-04-07T10:00",
                "2026-04-02T08:00, 480, 2026-04-02T17:00",
                "2026-04-02T08:00, 540, 2026-04-07T10:00",
                "2026-04-03T12:00, 30, 2026-04-07T09:30",
                "2026-04-04T12:00, 1, 2026-04-07T09:01",
                // Early May bank holiday after a weekend
                "2026-05-01T16:30, 60, 2026-05-05T09:30",
                // Christmas Eve to the Tuesday after the substitute day
                "2026-12-24T16:00, 120, 2026-12-29T10:00",
                // New Year's Day 2027 is not a configured holiday here, so it is a working day
                "2026-12-31T17:00, 60, 2027-01-01T10:00",
                // Weekends around both DST changes keep wall-clock opening hours
                "2026-03-27T16:00, 120, 2026-03-30T10:00",
                "2026-10-23T16:00, 120, 2026-10-26T10:00",
                // A 40-hour target in Easter week loses two days to holidays
                "2026-03-30T09:00, 2400, 2026-04-07T17:00",
                "2026-12-23T09:00, 960, 2026-12-24T17:00",
                "2026-12-23T09:00, 1020, 2026-12-29T10:00",
                // Closing-time edges
                "2026-03-02T16:59, 1, 2026-03-02T17:00",
                "2026-03-02T16:59, 2, 2026-03-03T09:01",
                "2026-03-01T23:30, 30, 2026-03-02T09:30",
                // 60 working days (480 business hours) across three bank holidays
                "2026-03-02T09:00, 28800, 2026-05-27T17:00",
        })
        void deadlines(String start, long minutes, String expected) {
            assertThat(calc.addBusinessTime(at(london, start), Duration.ofMinutes(minutes))).isEqualTo(at(london, expected));
        }

        @ParameterizedTest(name = "[{0}, {1}) = {2}min")
        @CsvSource({
                "2026-04-02T00:00, 2026-04-08T00:00, 960",
                "2026-12-21T00:00, 2027-01-04T00:00, 3840",
                "2026-03-27T12:00, 2026-03-30T12:00, 480",
                "2026-05-01T08:00, 2026-05-05T10:00, 540",
                "2026-04-03T09:00, 2026-04-03T17:00, 0",
        })
        void elapsed(String from, String to, long minutes) {
            assertThat(calc.businessTimeBetween(at(london, from), at(london, to))).isEqualTo(Duration.ofMinutes(minutes));
        }

        @ParameterizedTest(name = "{0} business={1}")
        @CsvSource({"2026-04-03T10:00, false", "2026-04-07T09:00, true", "2026-12-25T12:00, false",
                "2026-12-29T16:59, true", "2026-05-25T12:00, true"})
        void isBusinessTime(String local, boolean expected) {
            assertThat(calc.isBusinessTime(at(london, local))).isEqualTo(expected);
        }
    }

    /** Kolkata (no DST), Mon–Sat 10:00–18:00: a six-day week. */
    @Nested
    class SixDayWeek {

        private final ZoneId kolkata = ZoneId.of("Asia/Kolkata");
        private final BusinessTimeCalculator calc = new BusinessTimeCalculator(new BusinessCalendar(kolkata,
                days(List.of(WorkingWindow.of("10:00", "18:00")), DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                        DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), Set.of()));

        @Test
        void saturdayIsAWorkingDaySundayIsNot() {
            assertThat(calc.addBusinessTime(at(kolkata, "2026-03-06T17:00"), Duration.ofHours(2)))
                    .isEqualTo(at(kolkata, "2026-03-07T11:00"));
            assertThat(calc.addBusinessTime(at(kolkata, "2026-03-07T17:00"), Duration.ofHours(2)))
                    .isEqualTo(at(kolkata, "2026-03-09T11:00"));
        }

        @Test
        void aWeekHasFortyEightBusinessHours() {
            assertThat(calc.businessTimeBetween(at(kolkata, "2026-03-02T00:00"), at(kolkata, "2026-03-09T00:00")))
                    .isEqualTo(Duration.ofHours(48));
        }
    }

    /**
     * Sydney runs 24/7. Southern-hemisphere DST: clocks go back on Sun 5 Apr 2026 (a 25-hour
     * day) and forward on Sun 4 Oct 2026 (a 23-hour day).
     */
    @Nested
    class SouthernHemisphereDst {

        private final ZoneId sydney = ZoneId.of("Australia/Sydney");
        private final BusinessTimeCalculator calc = new BusinessTimeCalculator(new BusinessCalendar(sydney,
                days(List.of(WorkingWindow.of("00:00", "00:00")), DayOfWeek.values()), Set.of()));

        @ParameterizedTest(name = "{0} lasts {1}min")
        @CsvSource({"2026-04-05, 1500", "2026-10-04, 1380", "2026-06-07, 1440"})
        void dayLengthFollowsDst(String date, long minutes) {
            Instant start = at(sydney, date + "T00:00");
            Instant end = at(sydney, LocalDate.parse(date).plusDays(1) + "T00:00");
            assertThat(calc.businessTimeBetween(start, end)).isEqualTo(Duration.ofMinutes(minutes));
        }

        @Test
        void twentyFourBusinessHoursEndAnHourEarlyOnTheLongDay() {
            assertThat(calc.addBusinessTime(at(sydney, "2026-04-05T00:00"), Duration.ofHours(24)))
                    .isEqualTo(at(sydney, "2026-04-05T23:00"));
        }

        @Test
        void twentyFourBusinessHoursEndAnHourLateOnTheShortDay() {
            assertThat(calc.addBusinessTime(at(sydney, "2026-10-04T00:00"), Duration.ofHours(24)))
                    .isEqualTo(at(sydney, "2026-10-05T01:00"));
        }
    }

    /** Madrid split shift, weekdays 08:00–12:00 and 14:00–20:00 (10 h a day). */
    @Nested
    class SplitShift {

        private final ZoneId madrid = ZoneId.of("Europe/Madrid");
        private final BusinessTimeCalculator calc = new BusinessTimeCalculator(new BusinessCalendar(madrid,
                days(List.of(WorkingWindow.of("08:00", "12:00"), WorkingWindow.of("14:00", "20:00")), WEEKDAYS), Set.of()));

        @ParameterizedTest(name = "{0} + {1}min -> {2}")
        @CsvSource({
                "2026-03-02T11:00, 120, 2026-03-02T15:00",
                "2026-03-02T12:30, 10, 2026-03-02T14:10",
                "2026-03-02T19:30, 60, 2026-03-03T08:30",
                "2026-03-02T12:00, 360, 2026-03-02T20:00",
                // 1h on Friday, then the whole Monday morning window: ends at its close, not after lunch
                "2026-03-06T19:00, 300, 2026-03-09T12:00",
        })
        void deadlines(String start, long minutes, String expected) {
            assertThat(calc.addBusinessTime(at(madrid, start), Duration.ofMinutes(minutes))).isEqualTo(at(madrid, expected));
        }

        @Test
        void aDayHasTenBusinessHours() {
            assertThat(calc.businessTimeBetween(at(madrid, "2026-03-02T00:00"), at(madrid, "2026-03-03T00:00")))
                    .isEqualTo(Duration.ofHours(10));
        }
    }

    /** Extra clock edge cases on London weekday hours (no holidays). */
    @Nested
    class ClockEdges {

        private final ZoneId london = ZoneId.of("Europe/London");
        private final BusinessTimeCalculator calc = new BusinessTimeCalculator(
                BusinessCalendar.weekdays(london, WorkingWindow.of("09:00", "17:00"), Set.of()));

        @Test
        void pausingAtTheStartInstantConsumesNothing() {
            Instant t = at(london, "2026-03-02T10:00");
            assertThat(SlaClock.startedAt(t, Duration.ofHours(4)).pause(t, calc).consumed()).isZero();
        }

        @Test
        void consumedTimeOfAPausedClockDoesNotGrow() {
            SlaClock paused = SlaClock.startedAt(at(london, "2026-03-02T09:00"), Duration.ofHours(8))
                    .pause(at(london, "2026-03-02T11:00"), calc);
            assertThat(paused.consumedAt(at(london, "2026-03-20T12:00"), calc)).isEqualTo(Duration.ofHours(2));
        }

        @Test
        void resumingAfterTheWarningPointMakesTheWarningDueImmediately() {
            SlaClock clock = SlaClock.startedAt(at(london, "2026-03-02T09:00"), Duration.ofHours(5))
                    .pause(at(london, "2026-03-02T13:30"), calc) // 4.5h of 5h used: past the 4h warning point
                    .resume(at(london, "2026-03-03T09:00"));
            assertThat(clock.warningAt(calc)).contains(at(london, "2026-03-03T09:00"));
            assertThat(clock.deadline(calc)).contains(at(london, "2026-03-03T09:30"));
        }

        @Test
        void aPauseCoveringOnlyAWeekendChangesNothing() {
            SlaClock clock = SlaClock.startedAt(at(london, "2026-03-06T15:00"), Duration.ofHours(4));
            Instant before = clock.deadline(calc).orElseThrow();
            SlaClock cycled = clock.pause(at(london, "2026-03-06T17:30"), calc).resume(at(london, "2026-03-09T08:00"));
            assertThat(cycled.deadline(calc)).contains(before);
        }

        @Test
        void meetingAPausedClockKeepsItsConsumedTime() {
            SlaClock met = SlaClock.startedAt(at(london, "2026-03-02T09:00"), Duration.ofHours(8))
                    .pause(at(london, "2026-03-02T12:00"), calc)
                    .markMet(at(london, "2026-03-04T10:00"), calc);
            assertThat(met.consumed()).isEqualTo(Duration.ofHours(3));
            assertThat(met.isBreachedAt(at(london, "2026-03-30T00:00"), calc)).isFalse();
        }

        @Test
        void retargetingAMetClockKeepsItMet() {
            SlaClock met = SlaClock.startedAt(at(london, "2026-03-02T09:00"), Duration.ofHours(8))
                    .markMet(at(london, "2026-03-02T10:00"), calc);
            SlaClock retargeted = met.retarget(Duration.ofHours(1), at(london, "2026-03-02T12:00"), calc);
            assertThat(retargeted.isMet()).isTrue();
            assertThat(retargeted.metAt()).isEqualTo(met.metAt());
        }

        @Test
        void warningIsEightyPercentOfTheTargetInBusinessTime() {
            SlaClock clock = SlaClock.startedAt(at(london, "2026-03-02T16:00"), Duration.ofHours(10));
            // warning after 8h of business time: 1h Monday, 7h Tuesday
            assertThat(clock.warningAt(calc)).contains(at(london, "2026-03-03T16:00"));
            // deadline after 10h: 1h Monday, 8h Tuesday, 1h Wednesday
            assertThat(clock.deadline(calc)).contains(at(london, "2026-03-04T10:00"));
        }
    }
}
