package com.tickwise.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Dates used below (2026): Mon 2 Mar, Fri 6 Mar, Sun 8 Mar (US spring forward),
 * Sun 29 Mar (EU spring forward), Sun 25 Oct (EU fall back), Sun 1 Nov (US fall back).
 */
class BusinessTimeCalculatorTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    /** Mon–Fri 09:00–17:00 New York, no holidays. */
    private final BusinessTimeCalculator nineToFive =
            new BusinessTimeCalculator(BusinessCalendar.weekdays(NEW_YORK, WorkingWindow.of("09:00", "17:00"), Set.of()));

    private static Instant ny(String localDateTime) {
        return at(NEW_YORK, localDateTime);
    }

    private static Instant at(ZoneId zone, String localDateTime) {
        return ZonedDateTime.of(LocalDateTime.parse(localDateTime), zone).toInstant();
    }

    private static Duration h(long hours) {
        return Duration.ofHours(hours);
    }

    private static Duration m(long minutes) {
        return Duration.ofMinutes(minutes);
    }

    @Nested
    class AddBusinessTime {

        @ParameterizedTest(name = "{0} + {1}min -> {2}")
        @CsvSource({
                // within one window
                "2026-03-02T10:00, 120, 2026-03-02T12:00",
                // overflows the end of the day into the next morning
                "2026-03-02T16:00, 120, 2026-03-03T10:00",
                // before opening: the clock starts at 09:00
                "2026-03-02T07:00, 60, 2026-03-02T10:00",
                // overnight arrival: nothing counts until the next morning
                "2026-03-02T20:00, 60, 2026-03-03T10:00",
                // Friday afternoon rolls over the weekend
                "2026-03-06T16:30, 60, 2026-03-09T09:30",
                // created on a Saturday
                "2026-03-07T12:00, 30, 2026-03-09T09:30",
                // created Sunday night just before midnight
                "2026-03-08T23:59, 1, 2026-03-09T09:01",
                // exactly at opening
                "2026-03-02T09:00, 1, 2026-03-02T09:01",
                // exactly at closing: nothing left today
                "2026-03-02T17:00, 1, 2026-03-03T09:01",
        })
        void rollsAcrossClosedTime(String start, long minutes, String expected) {
            assertThat(nineToFive.addBusinessTime(ny(start), m(minutes))).isEqualTo(ny(expected));
        }

        @Test
        void deadlineThatUsesUpAWindowExactlyIsThatWindowsEndNotTheNextMorning() {
            assertThat(nineToFive.addBusinessTime(ny("2026-03-02T16:00"), h(1))).isEqualTo(ny("2026-03-02T17:00"));
            assertThat(nineToFive.addBusinessTime(ny("2026-03-02T09:00"), h(8))).isEqualTo(ny("2026-03-02T17:00"));
        }

        @Test
        void zeroDurationReturnsTheStartEvenOutsideBusinessHours() {
            Instant saturday = ny("2026-03-07T12:00");
            assertThat(nineToFive.addBusinessTime(saturday, Duration.ZERO)).isEqualTo(saturday);
        }

        @Test
        void negativeDurationIsRejected() {
            assertThatThrownBy(() -> nineToFive.addBusinessTime(ny("2026-03-02T10:00"), m(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void deadlinesAcrossManyDays() {
            Instant monday9 = ny("2026-03-02T09:00");
            // one working week = 40 business hours, ending Friday 17:00 (which crosses the US DST change)
            assertThat(nineToFive.addBusinessTime(monday9, h(40))).isEqualTo(ny("2026-03-06T17:00"));
            assertThat(nineToFive.addBusinessTime(monday9, h(41))).isEqualTo(ny("2026-03-09T10:00"));
            // four working weeks
            assertThat(nineToFive.addBusinessTime(monday9, h(160))).isEqualTo(ny("2026-03-27T17:00"));
        }

        @Test
        void secondPrecisionIsPreserved() {
            Instant start = ny("2026-03-02T10:00").plusSeconds(17);
            assertThat(nineToFive.addBusinessTime(start, Duration.ofSeconds(90))).isEqualTo(start.plusSeconds(90));
        }

        @Test
        void resultIsExpressedInUtc() {
            // 16:00 EST (UTC-5) on Friday + 2h -> Monday 10:00 EDT (UTC-4) after the spring-forward weekend
            assertThat(nineToFive.addBusinessTime(Instant.parse("2026-03-06T21:00:00Z"), h(2)))
                    .isEqualTo(Instant.parse("2026-03-09T14:00:00Z"));
        }
    }

    @Nested
    class Holidays {

        private final BusinessTimeCalculator withHolidays = new BusinessTimeCalculator(BusinessCalendar.weekdays(
                NEW_YORK, WorkingWindow.of("09:00", "17:00"),
                Set.of(LocalDate.parse("2026-03-09"), // a Monday
                        LocalDate.parse("2026-12-24"), LocalDate.parse("2026-12-25"),
                        LocalDate.parse("2026-12-31"), LocalDate.parse("2027-01-01"))));

        @Test
        void holidayAfterAWeekendIsSkipped() {
            assertThat(withHolidays.addBusinessTime(ny("2026-03-06T16:00"), h(2))).isEqualTo(ny("2026-03-10T10:00"));
        }

        @Test
        void ticketCreatedOnAHolidayStartsCountingOnTheNextWorkingDay() {
            assertThat(withHolidays.addBusinessTime(ny("2026-03-09T11:00"), m(30))).isEqualTo(ny("2026-03-10T09:30"));
        }

        @Test
        void holidayClustersOverChristmasAndNewYear() {
            // Wed 23 Dec 16:00 + 3h: 1h on Wed, Thu/Fri holidays, weekend, then 2h on Mon 28 Dec
            assertThat(withHolidays.addBusinessTime(ny("2026-12-23T16:00"), h(3))).isEqualTo(ny("2026-12-28T11:00"));
            // Wed 30 Dec 16:00 + 9h: 1h Wed, Thu/Fri holidays, weekend, full Mon 4 Jan
            assertThat(withHolidays.addBusinessTime(ny("2026-12-30T16:00"), h(9))).isEqualTo(ny("2027-01-04T17:00"));
        }

        @Test
        void holidayRemovesOnlyItsOwnDate() {
            assertThat(withHolidays.businessTimeBetween(ny("2026-03-06T00:00"), ny("2026-03-11T00:00"))).isEqualTo(h(16));
        }

        @Test
        void calendarWithNoReachableBusinessTimeFailsFastInsteadOfLooping() {
            BusinessCalendar onlyOneDay = new BusinessCalendar(NEW_YORK,
                    Map.of(DayOfWeek.MONDAY, List.of(WorkingWindow.of("09:00", "17:00"))),
                    allMondaysFrom(LocalDate.parse("2026-03-02"), BusinessTimeCalculator.MAX_SEARCH_DAYS / 7 + 2));
            BusinessTimeCalculator calc = new BusinessTimeCalculator(onlyOneDay);

            assertThatThrownBy(() -> calc.addBusinessTime(ny("2026-03-02T08:00"), h(1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No business time");
        }

        private static Set<LocalDate> allMondaysFrom(LocalDate first, int count) {
            java.util.HashSet<LocalDate> result = new java.util.HashSet<>();
            for (int i = 0; i < count; i++) {
                result.add(first.plusWeeks(i));
            }
            return result;
        }
    }

    @Nested
    class DaylightSavingTime {

        /** Every day 01:00–04:00: a window that contains the 02:00 (US) / 02:00–03:00 (EU) transition. */
        private BusinessTimeCalculator nightWindow(ZoneId zone) {
            EnumMap<DayOfWeek, List<WorkingWindow>> map = new EnumMap<>(DayOfWeek.class);
            for (DayOfWeek d : DayOfWeek.values()) {
                map.put(d, List.of(WorkingWindow.of("01:00", "04:00")));
            }
            return new BusinessTimeCalculator(new BusinessCalendar(zone, map, Set.of()));
        }

        @Test
        void springForwardWindowIsAnHourShorter() {
            BusinessTimeCalculator calc = nightWindow(NEW_YORK);
            assertThat(calc.businessTimeBetween(ny("2026-03-08T00:00"), ny("2026-03-08T05:00"))).isEqualTo(h(2));
            // 3h from 01:00 on transition day: 2h that night, 1h the next night
            assertThat(calc.addBusinessTime(ny("2026-03-08T01:00"), h(3))).isEqualTo(ny("2026-03-09T02:00"));
        }

        @Test
        void fallBackWindowIsAnHourLonger() {
            BusinessTimeCalculator calc = nightWindow(BERLIN);
            assertThat(calc.businessTimeBetween(at(BERLIN, "2026-10-25T00:00"), at(BERLIN, "2026-10-25T05:00")))
                    .isEqualTo(h(4));
            assertThat(calc.addBusinessTime(at(BERLIN, "2026-10-25T01:00"), h(4))).isEqualTo(at(BERLIN, "2026-10-25T04:00"));
        }

        @Test
        void europeanSpringForward() {
            BusinessTimeCalculator calc = nightWindow(BERLIN);
            assertThat(calc.businessTimeBetween(at(BERLIN, "2026-03-29T00:00"), at(BERLIN, "2026-03-29T05:00")))
                    .isEqualTo(h(2));
        }

        @Test
        void windowStartingInsideTheGapIsShiftedForward() {
            // 02:30 does not exist in New York on 8 Mar 2026; it is treated as 03:30 EDT.
            BusinessCalendar cal = new BusinessCalendar(NEW_YORK,
                    Map.of(DayOfWeek.SUNDAY, List.of(WorkingWindow.of("02:30", "05:00"))), Set.of());
            BusinessTimeCalculator calc = new BusinessTimeCalculator(cal);

            assertThat(calc.businessTimeBetween(ny("2026-03-08T00:00"), ny("2026-03-08T06:00"))).isEqualTo(m(90));
            assertThat(calc.addBusinessTime(ny("2026-03-08T00:00"), m(1))).isEqualTo(Instant.parse("2026-03-08T07:31:00Z"));
        }

        @Test
        void usFallBackWeekdayDeadlineKeepsWallClockHours() {
            // Fri 30 Oct 16:00 EDT + 2h -> Mon 2 Nov 10:00 EST (UTC-5)
            assertThat(nineToFive.addBusinessTime(ny("2026-10-30T16:00"), h(2))).isEqualTo(Instant.parse("2026-11-02T15:00:00Z"));
        }

        @Test
        void zoneWithoutDstIsUnaffected() {
            BusinessTimeCalculator india = new BusinessTimeCalculator(
                    BusinessCalendar.weekdays(KOLKATA, WorkingWindow.of("10:00", "19:00"), Set.of()));
            assertThat(india.addBusinessTime(at(KOLKATA, "2026-03-06T18:00"), h(2))).isEqualTo(at(KOLKATA, "2026-03-09T11:00"));
        }
    }

    @Nested
    class ComplexWindows {

        @Test
        void lunchBreakIsNotCounted() {
            BusinessTimeCalculator calc = new BusinessTimeCalculator(new BusinessCalendar(NEW_YORK,
                    weekdays(List.of(WorkingWindow.of("09:00", "12:00"), WorkingWindow.of("13:00", "17:00"))), Set.of()));

            assertThat(calc.addBusinessTime(ny("2026-03-02T11:30"), h(1))).isEqualTo(ny("2026-03-02T13:30"));
            assertThat(calc.addBusinessTime(ny("2026-03-02T12:15"), m(15))).isEqualTo(ny("2026-03-02T13:15"));
            assertThat(calc.businessTimeBetween(ny("2026-03-02T00:00"), ny("2026-03-03T00:00"))).isEqualTo(h(7));
        }

        @Test
        void overnightShiftThatStartedTheEveningBeforeIsCounted() {
            // Night shift Mon–Fri 22:00–06:00 (the Friday shift ends Saturday 06:00)
            BusinessTimeCalculator calc = new BusinessTimeCalculator(new BusinessCalendar(NEW_YORK,
                    weekdays(List.of(WorkingWindow.of("22:00", "06:00"))), Set.of()));

            // Tue 05:00 is inside Monday night's shift: 1h left there, then 1h of Tuesday night's
            assertThat(calc.addBusinessTime(ny("2026-03-03T05:00"), h(2))).isEqualTo(ny("2026-03-03T23:00"));
            // Saturday 03:00 is still inside Friday night's shift
            assertThat(calc.isBusinessTime(ny("2026-03-07T03:00"))).isTrue();
            // Sunday night has no shift, so a Sunday 23:00 arrival waits until Monday 22:00
            assertThat(calc.addBusinessTime(ny("2026-03-01T23:00"), m(30))).isEqualTo(ny("2026-03-02T22:30"));
        }

        @Test
        void holidayCancelsTheOvernightShiftStartingThatEvening() {
            BusinessTimeCalculator calc = new BusinessTimeCalculator(new BusinessCalendar(NEW_YORK,
                    weekdays(List.of(WorkingWindow.of("22:00", "06:00"))), Set.of(LocalDate.parse("2026-03-03"))));

            assertThat(calc.isBusinessTime(ny("2026-03-04T02:00"))).isFalse();
            assertThat(calc.isBusinessTime(ny("2026-03-03T02:00"))).isTrue();
        }

        @Test
        void touchingWindowsAcrossMidnightAreMergedAndCountedOnce() {
            EnumMap<DayOfWeek, List<WorkingWindow>> map = new EnumMap<>(DayOfWeek.class);
            for (DayOfWeek d : DayOfWeek.values()) {
                map.put(d, List.of(WorkingWindow.of("00:00", "00:00")));
            }
            BusinessTimeCalculator always = new BusinessTimeCalculator(new BusinessCalendar(BERLIN, map, Set.of()));

            assertThat(always.addBusinessTime(Instant.parse("2026-03-02T10:00:00Z"), h(72)))
                    .isEqualTo(Instant.parse("2026-03-05T10:00:00Z"));
            // 24/7 business time over the EU fall-back day is the real 25 hours
            assertThat(always.businessTimeBetween(at(BERLIN, "2026-10-25T00:00"), at(BERLIN, "2026-10-26T00:00")))
                    .isEqualTo(h(25));
        }

        @Test
        void windowRunningToMidnight() {
            BusinessTimeCalculator calc = new BusinessTimeCalculator(
                    BusinessCalendar.weekdays(NEW_YORK, WorkingWindow.of("16:00", "00:00"), Set.of()));
            assertThat(calc.addBusinessTime(ny("2026-03-02T23:00"), h(2))).isEqualTo(ny("2026-03-03T17:00"));
        }

        private static Map<DayOfWeek, List<WorkingWindow>> weekdays(List<WorkingWindow> windows) {
            EnumMap<DayOfWeek, List<WorkingWindow>> map = new EnumMap<>(DayOfWeek.class);
            for (DayOfWeek d : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
                map.put(d, windows);
            }
            return map;
        }
    }

    @Nested
    class BusinessTimeBetween {

        @ParameterizedTest(name = "[{0}, {1}) = {2}min")
        @CsvSource({
                "2026-03-02T10:00, 2026-03-02T11:30, 90",
                "2026-03-02T08:00, 2026-03-02T18:00, 480",
                "2026-03-02T16:00, 2026-03-03T10:00, 120",
                "2026-03-06T16:00, 2026-03-09T10:00, 120",
                "2026-03-07T00:00, 2026-03-09T00:00, 0",
                "2026-03-02T17:00, 2026-03-03T09:00, 0",
                "2026-03-02T09:00, 2026-03-09T09:00, 2400",
        })
        void countsOnlyWorkingTime(String from, String to, long minutes) {
            assertThat(nineToFive.businessTimeBetween(ny(from), ny(to))).isEqualTo(m(minutes));
        }

        @Test
        void reversedOrEmptyRangeIsZero() {
            assertThat(nineToFive.businessTimeBetween(ny("2026-03-02T12:00"), ny("2026-03-02T10:00"))).isZero();
            assertThat(nineToFive.businessTimeBetween(ny("2026-03-02T12:00"), ny("2026-03-02T12:00"))).isZero();
        }

        @Test
        void isBusinessTimeTreatsWindowStartAsInsideAndEndAsOutside() {
            assertThat(nineToFive.isBusinessTime(ny("2026-03-02T09:00"))).isTrue();
            assertThat(nineToFive.isBusinessTime(ny("2026-03-02T16:59"))).isTrue();
            assertThat(nineToFive.isBusinessTime(ny("2026-03-02T17:00"))).isFalse();
            assertThat(nineToFive.isBusinessTime(ny("2026-03-07T12:00"))).isFalse();
        }
    }

    @Nested
    class Invariants {

        private final List<BusinessTimeCalculator> calendars = List.of(
                nineToFive,
                new BusinessTimeCalculator(new BusinessCalendar(BERLIN, Map.of(
                        DayOfWeek.MONDAY, List.of(WorkingWindow.of("08:00", "12:00"), WorkingWindow.of("13:00", "17:30")),
                        DayOfWeek.WEDNESDAY, List.of(WorkingWindow.of("22:00", "06:00")),
                        DayOfWeek.SATURDAY, List.of(WorkingWindow.of("00:00", "00:00"))),
                        Set.of(LocalDate.parse("2026-10-26"), LocalDate.parse("2026-12-25")))));

        /** For random starts (including DST weeks) and durations, between(start, add(start, d)) == d. */
        @Test
        void addAndBetweenAreInverse() {
            Random random = new Random(20261101L);
            Instant base = Instant.parse("2026-01-01T00:00:00Z");
            for (BusinessTimeCalculator calc : calendars) {
                for (int i = 0; i < 2_000; i++) {
                    Instant start = base.plusSeconds(random.nextLong(365L * 24 * 3600));
                    Duration amount = Duration.ofMinutes(1 + random.nextInt(60 * 24 * 10));
                    Instant deadline = calc.addBusinessTime(start, amount);

                    assertThat(calc.businessTimeBetween(start, deadline)).as("start=%s amount=%s", start, amount).isEqualTo(amount);
                    assertThat(calc.businessTimeBetween(start, deadline.minusSeconds(1)))
                            .as("deadline must be the earliest such instant").isLessThan(amount);
                }
            }
        }

        @Test
        void betweenIsAdditiveOverAnySplitPoint() {
            Random random = new Random(7L);
            Instant base = Instant.parse("2026-02-20T00:00:00Z");
            for (BusinessTimeCalculator calc : calendars) {
                for (int i = 0; i < 500; i++) {
                    Instant a = base.plusSeconds(random.nextInt(60 * 24 * 3600));
                    Instant b = a.plusSeconds(random.nextInt(14 * 24 * 3600));
                    Instant c = b.plusSeconds(random.nextInt(14 * 24 * 3600));
                    assertThat(calc.businessTimeBetween(a, b).plus(calc.businessTimeBetween(b, c)))
                            .isEqualTo(calc.businessTimeBetween(a, c));
                }
            }
        }
    }

    @Nested
    class CalendarValidation {

        @Test
        void overlappingWindowsOnOneDayAreRejected() {
            assertThatThrownBy(() -> new BusinessCalendar(NEW_YORK, Map.of(DayOfWeek.MONDAY,
                    List.of(WorkingWindow.of("09:00", "13:00"), WorkingWindow.of("12:00", "17:00"))), Set.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("overlap");
        }

        @Test
        void onlyTheLastWindowMayCrossMidnight() {
            assertThatThrownBy(() -> new BusinessCalendar(NEW_YORK, Map.of(DayOfWeek.MONDAY,
                    List.of(WorkingWindow.of("22:00", "02:00"), WorkingWindow.of("23:00", "23:30"))), Set.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void calendarWithoutWorkingTimeIsRejected() {
            assertThatThrownBy(() -> new BusinessCalendar(NEW_YORK, Map.of(DayOfWeek.MONDAY, List.of()), Set.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void windowsAreMinutePrecision() {
            assertThatThrownBy(() -> WorkingWindow.of("09:00:30", "17:00"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void nominalLength() {
            assertThat(WorkingWindow.of("09:00", "17:00").nominalLength()).isEqualTo(h(8));
            assertThat(WorkingWindow.of("22:00", "06:00").nominalLength()).isEqualTo(h(8));
            assertThat(WorkingWindow.of("00:00", "00:00").nominalLength()).isEqualTo(h(24));
        }
    }
}
