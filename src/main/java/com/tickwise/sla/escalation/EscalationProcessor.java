package com.tickwise.sla.escalation;

import com.tickwise.shared.domain.Times;
import com.tickwise.sla.SlaService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Fires due escalation jobs. Invoked by the worker's scheduler, or directly from tests. */
@Component
public class EscalationProcessor {

    private static final Logger log = LoggerFactory.getLogger(EscalationProcessor.class);

    static final int BATCH_SIZE = 100;
    static final Duration LEASE = Duration.ofSeconds(30);
    static final int MAX_ATTEMPTS = 5;

    private final RedisEscalationQueue queue;
    private final SlaService slaService;
    private final Clock clock;

    EscalationProcessor(RedisEscalationQueue queue, SlaService slaService, Clock clock) {
        this.queue = queue;
        this.slaService = slaService;
        this.clock = clock;
    }

    /** Processes one batch of due jobs; returns how many were claimed. */
    public int processDue() {
        Instant now = Times.now(clock);
        List<EscalationQueue.Claimed> claimed = queue.claimDue(now, BATCH_SIZE, LEASE);
        for (EscalationQueue.Claimed c : claimed) {
            try {
                SlaService.DueOutcome outcome = slaService.handleDue(c.job(), now);
                if (outcome.done()) {
                    queue.complete(c);
                } else {
                    queue.schedule(c.job(), outcome.rescheduleAt());
                }
            } catch (RuntimeException e) {
                long attempts = queue.recordFailure(c.job());
                if (attempts >= MAX_ATTEMPTS) {
                    log.error("Escalation {} failed {} times; dropping it", c.job().key(), attempts, e);
                    queue.cancel(c.job());
                } else {
                    // Left leased: it is redelivered when the lease expires.
                    log.warn("Escalation {} failed (attempt {}); will retry", c.job().key(), attempts, e);
                }
            }
        }
        return claimed.size();
    }

    /** Re-schedules jobs for every running clock, repairing anything lost while Redis was unavailable. */
    public long reconcile() {
        UUID after = new UUID(0, 0);
        long batches = 0;
        while (true) {
            var last = slaService.resyncRunning(after, 500);
            if (last.isEmpty()) {
                return batches;
            }
            after = last.get();
            batches++;
        }
    }
}
