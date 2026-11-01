package com.tickwise.shared.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Times {

    private Times() {}

    /**
     * The current instant at millisecond precision. Postgres stores microseconds and the JVM clock
     * may report nanoseconds; truncating once means a timestamp read back equals the one written.
     */
    public static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
