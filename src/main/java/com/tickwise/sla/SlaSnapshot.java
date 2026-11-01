package com.tickwise.sla;

import java.time.Instant;
import java.util.Objects;
import java.util.stream.Stream;

public record SlaSnapshot(SlaTimer firstResponse, SlaTimer resolution) {

    /** Earliest deadline of a running clock, used to sort the agent queue; null when nothing runs. */
    public Instant nextDueAt() {
        return earliest(Stream.of(firstResponse, resolution).map(SlaTimer::dueAt));
    }

    public Instant nextWarningAt() {
        return earliest(Stream.of(firstResponse, resolution).map(SlaTimer::warningAt));
    }

    public boolean anyBreached() {
        return firstResponse.breached() || resolution.breached();
    }

    private static Instant earliest(Stream<Instant> instants) {
        return instants.filter(Objects::nonNull).min(Instant::compareTo).orElse(null);
    }
}
