package com.tickwise.sla;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Mon–Fri 09:00–17:00 New York; Mon 2 Mar 2026 is a Monday and Wed 4 Mar is a holiday in one test. */
class SlaClockTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private final BusinessTimeCalculator calc =
            new BusinessTimeCalculator(BusinessCalendar.weekdays(NEW_YORK, WorkingWindow.of("09:00", "17:00"), Set.of()));

    private static Instant ny(String local) {
        return ZonedDateTime.of(LocalDateTime.parse(local), NEW_YORK).toInstant();
    }

    @Test
    void freshClockDeadlineIsStartPlusTargetInBusinessTime() {
        SlaClock clock = SlaClock.startedAt(ny("2026-03-02T15:00"), Duration.ofHours(4));

        assertThat(clock.deadline(calc)).contains(ny("2026-03-03T11:00"));
        assertThat(clock.isRunning()).isTrue();
    }

    @Test
    void warningFiresAtEightyPercentOfTheTarget() {
        SlaClock clock = SlaClock.startedAt(ny("2026-03-02T09:00"), Duration.ofHours(10));

        assertThat(clock.warningAt(calc)).contains(ny("2026-03-02T17:00"));
        assertThat(clock.deadline(calc)).contains(ny("2026-03-03T11:00"));
    }

    @Test
    void pausingStopsTheClockAndResumingPushesTheDeadlineByThePausedBusinessTime() {
        SlaClock clock = SlaClock.startedAt(ny("2026-03-02T09:00"), Duration.ofHours(8));

        SlaClock paused = clock.pause(ny("2026-03-02T11:00"), calc);
        assertThat(paused.isPaused()).isTrue();
        assertThat(paused.consumed()).isEqualTo(Duration.ofHours(2));
        assertThat(paused.deadline(calc)).isEmpty();

        // customer replies Tuesday 10:00; 6h remain -> Tuesday 16:00
        SlaClock resumed = paused.resume(ny("2026-03-03T10:00"));
        assertThat(resumed.deadline(calc)).contains(ny("2026-03-03T16:00"));
    }

    @Test
    void pauseAndResumeOutsideBusinessHoursLeavesTheDeadlineUnchanged() {
        SlaClock clock = SlaClock.startedAt(ny("2026-03-02T16:00"), Duration.ofHours(2));
        Instant original = clock.deadline(calc).orElseThrow();

        SlaClock cycled = clock.pause(ny("2026-03-02T19:00"), calc).resume(ny("2026-03-03T07:00"));

        assertThat(cycled.deadline(calc)).contains(original);
    }

    @Test
    void repeatedPauseCyclesAccumulateConsumedTime() {
        SlaClock clock = SlaClock.startedAt(ny("2026-03-02T09:00"), Duration.ofHours(8))
                .pause(ny("2026-03-02T10:00"), calc).resume(ny("2026-03-02T12:00"))
                .pause(ny("2026-03-02T13:00"), calc).resume(ny("2026-03-02T15:00"));

        assertThat(clock.consumed()).isEqualTo(Duration.ofHours(2));
        assertThat(clock.consumedAt(ny("2026-03-02T16:00"), calc)).isEqualTo(Duration.ofHours(3));
        assertThat(clock.deadline(calc)).contains(ny("2026-03-03T13:00"));
    }

    @Test
    void pausingTwiceOrResumingARunningClockIsANoOp() {
        SlaClock paused = SlaClock.startedAt(ny("2026-03-02T09:00"), Duration.ofHours(8)).pause(ny("2026-03-02T10:00"), calc);
        assertThat(paused.pause(ny("2026-03-02T15:00"), calc)).isEqualTo(paused);

        SlaClock running = paused.resume(ny("2026-03-02T11:00"));
        assertThat(running.resume(ny("2026-03-02T14:00"))).isEqualTo(running);
    }

    @Test
    void metBeforeDeadlineIsNotBreached() {
        SlaClock met = SlaClock.startedAt(ny("2026-03-02T09:00"), Duration.ofHours(1)).markMet(ny("2026-03-02T09:59"), calc);

        assertThat(met.isMet()).isTrue();
        assertThat(met.deadline(calc)).isEmpty();
        assertThat(met.isBreachedAt(ny("2026-03-10T00:00"), calc)).isFalse();
    }

    @Test
    void metExactlyAtDeadlineIsNotBreachedButOneSecondLateIs() {
        SlaClock clock = SlaClock.startedAt(ny("2026-03-02T09:00"), Duration.ofHours(1));

        assertThat(clock.markMet(ny("2026-03-02T10:00"), calc).isBreachedAt(ny("2026-03-03T00:00"), calc)).isFalse();
        assertThat(clock.markMet(ny("2026-03-02T10:00").plusSeconds(1), calc).isBreachedAt(ny("2026-03-03T00:00"), calc)).isTrue();
    }

    @Test
    void openClockIsBreachedOnceConsumedExceedsTarget() {
        SlaClock clock = SlaClock.startedAt(ny("2026-03-02T16:00"), Duration.ofHours(2));

        assertThat(clock.isBreachedAt(ny("2026-03-02T23:00"), calc)).isFalse(); // only 1h of business time so far
        assertThat(clock.isBreachedAt(ny("2026-03-03T10:00"), calc)).isFalse(); // exactly at the deadline
        assertThat(clock.isBreachedAt(ny("2026-03-03T10:01"), calc)).isTrue();
    }

    @Test
    void raisingPriorityShortensTheDeadlineButKeepsConsumedTime() {
        SlaClock clock = SlaClock.startedAt(ny("2026-03-02T09:00"), Duration.ofHours(16));

        SlaClock urgent = clock.retarget(Duration.ofHours(4), ny("2026-03-02T11:00"), calc);

        assertThat(urgent.consumed()).isEqualTo(Duration.ofHours(2));
        assertThat(urgent.runningSince()).isEqualTo(ny("2026-03-02T11:00"));
        assertThat(urgent.deadline(calc)).contains(ny("2026-03-02T13:00"));
    }

    @Test
    void loweringPriorityExtendsTheDeadline() {
        SlaClock clock = SlaClock.startedAt(ny("2026-03-02T09:00"), Duration.ofHours(4));

        assertThat(clock.retarget(Duration.ofHours(16), ny("2026-03-02T10:00"), calc).deadline(calc))
                .contains(ny("2026-03-03T17:00"));
    }

    @Test
    void retargetBelowAlreadyConsumedTimeIsDueImmediately() {
        SlaClock clock = SlaClock.startedAt(ny("2026-03-02T09:00"), Duration.ofHours(8));
        Instant now = ny("2026-03-02T14:00");

        SlaClock tight = clock.retarget(Duration.ofHours(1), now, calc);

        assertThat(tight.deadline(calc)).contains(now);
        assertThat(tight.warningAt(calc)).contains(now);
        assertThat(tight.isBreachedAt(now.plusSeconds(1), calc)).isTrue();
    }

    @Test
    void retargetWhilePausedAppliesOnResume() {
        SlaClock paused = SlaClock.startedAt(ny("2026-03-02T09:00"), Duration.ofHours(8)).pause(ny("2026-03-02T10:00"), calc);

        SlaClock retargeted = paused.retarget(Duration.ofHours(2), ny("2026-03-02T12:00"), calc);
        assertThat(retargeted.isPaused()).isTrue();
        assertThat(retargeted.resume(ny("2026-03-02T13:00")).deadline(calc)).contains(ny("2026-03-02T14:00"));
    }

    @Test
    void reopeningAResolvedTicketResumesWithTheTimeAlreadyConsumed() {
        SlaClock resolved = SlaClock.startedAt(ny("2026-03-02T09:00"), Duration.ofHours(8)).markMet(ny("2026-03-02T12:00"), calc);

        SlaClock reopened = resolved.reopen(ny("2026-03-03T09:00"));

        assertThat(reopened.isRunning()).isTrue();
        assertThat(reopened.deadline(calc)).contains(ny("2026-03-03T14:00"));
    }

    @Test
    void holidayDuringAPauseIsHandledByTheCalendar() {
        BusinessTimeCalculator withHoliday = new BusinessTimeCalculator(BusinessCalendar.weekdays(NEW_YORK,
                WorkingWindow.of("09:00", "17:00"), Set.of(LocalDate.parse("2026-03-04"))));
        SlaClock clock = SlaClock.startedAt(ny("2026-03-03T13:00"), Duration.ofHours(8));

        // runs Tue 13:00–17:00 (4h), resumes Wed (holiday) -> Thu 09:00 + 4h
        assertThat(clock.deadline(withHoliday)).contains(ny("2026-03-05T13:00"));
    }
}
