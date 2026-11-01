package com.tickwise.sla;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable state of one SLA target (first response or resolution) for one ticket.
 *
 * <p>The clock accumulates <em>consumed</em> business time. While running, consumed time grows
 * from {@code runningSince}. Pausing (status PENDING_CUSTOMER) folds the running segment into
 * {@code consumed}, and resuming starts a new segment. The deadline is always derived, never
 * stored as truth: {@code runningSince + (target - consumed)} in business time. So pausing,
 * resuming and re-prioritising cannot drift.
 *
 * <p>Every transition takes the current instant explicitly, which keeps the class deterministic.
 */
public record SlaClock(Duration target, Duration consumed, Instant runningSince, Instant metAt) {

    /** Percentage of the target at which the WARNING escalation fires. */
    public static final int WARNING_PERCENT = 80;

    public SlaClock {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(consumed, "consumed");
        if (target.isNegative() || consumed.isNegative()) {
            throw new IllegalArgumentException("SLA durations must not be negative");
        }
        if (runningSince != null && metAt != null) {
            throw new IllegalArgumentException("A met clock cannot be running");
        }
    }

    public static SlaClock startedAt(Instant now, Duration target) {
        return new SlaClock(target, Duration.ZERO, now, null);
    }

    public boolean isRunning() {
        return runningSince != null;
    }

    public boolean isMet() {
        return metAt != null;
    }

    public boolean isPaused() {
        return runningSince == null && metAt == null;
    }

    /** Business time consumed up to {@code now}, including the running segment. */
    public Duration consumedAt(Instant now, BusinessTimeCalculator calc) {
        if (!isRunning()) {
            return consumed;
        }
        return consumed.plus(calc.businessTimeBetween(runningSince, now));
    }

    public SlaClock pause(Instant now, BusinessTimeCalculator calc) {
        if (!isRunning()) {
            return this;
        }
        return new SlaClock(target, consumedAt(now, calc), null, null);
    }

    public SlaClock resume(Instant now) {
        if (!isPaused()) {
            return this;
        }
        return new SlaClock(target, consumed, now, null);
    }

    /** The target was met at {@code now}; the clock stops for good unless {@link #reopen reopened}. */
    public SlaClock markMet(Instant now, BusinessTimeCalculator calc) {
        if (isMet()) {
            return this;
        }
        return new SlaClock(target, consumedAt(now, calc), null, now);
    }

    /** Undo {@link #markMet}: the consumed time is kept and the clock runs again from {@code now}. */
    public SlaClock reopen(Instant now) {
        if (!isMet()) {
            return this;
        }
        return new SlaClock(target, consumed, now, null);
    }

    /**
     * Change the target, for example after a priority change. Time already consumed still counts,
     * so if the new target is already used up, the deadline is {@code now}.
     */
    public SlaClock retarget(Duration newTarget, Instant now, BusinessTimeCalculator calc) {
        if (isRunning()) {
            return new SlaClock(newTarget, consumedAt(now, calc), now, null);
        }
        return new SlaClock(newTarget, consumed, null, metAt);
    }

    /** When the target runs out; empty while paused or met. */
    public Optional<Instant> deadline(BusinessTimeCalculator calc) {
        return timeWhenConsumed(target, calc);
    }

    /** When {@value #WARNING_PERCENT}% of the target is used up; empty while paused or met. */
    public Optional<Instant> warningAt(BusinessTimeCalculator calc) {
        return timeWhenConsumed(target.multipliedBy(WARNING_PERCENT).dividedBy(100), calc);
    }

    /** Met late, or (if still open) more business time consumed than the target allows. */
    public boolean isBreachedAt(Instant now, BusinessTimeCalculator calc) {
        return consumedAt(isMet() ? metAt : now, calc).compareTo(target) > 0;
    }

    private Optional<Instant> timeWhenConsumed(Duration threshold, BusinessTimeCalculator calc) {
        if (!isRunning()) {
            return Optional.empty();
        }
        Duration remaining = threshold.minus(consumed);
        if (remaining.isNegative() || remaining.isZero()) {
            return Optional.of(runningSince);
        }
        return Optional.of(calc.addBusinessTime(runningSince, remaining));
    }
}
