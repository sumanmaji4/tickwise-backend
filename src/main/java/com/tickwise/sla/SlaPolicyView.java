package com.tickwise.sla;

import com.tickwise.shared.domain.Priority;
import java.time.Duration;

/** Business-time targets for one priority. */
public record SlaPolicyView(Priority priority, int firstResponseMinutes, int resolutionMinutes) {

    public Duration target(SlaMetric metric) {
        return Duration.ofMinutes(metric == SlaMetric.FIRST_RESPONSE ? firstResponseMinutes : resolutionMinutes);
    }
}
