package com.tickwise.sla.escalation;

import com.tickwise.sla.SlaMetric;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Keeps the delay queue in step with SLA clocks. Changes are applied only <em>after</em> the
 * database transaction commits, so a rolled-back change never leaves a job behind. If Redis is
 * unavailable at that moment, the job is lost until the worker's start-up reconciliation; this
 * is safe because handlers re-check the clock (see ADR 0005).
 */
@Component
public class EscalationScheduler {

    private static final Logger log = LoggerFactory.getLogger(EscalationScheduler.class);

    private final EscalationQueue queue;

    EscalationScheduler(EscalationQueue queue) {
        this.queue = queue;
    }

    /** {@code warnAt} / {@code breachAt} null means "nothing to fire": the job is cancelled. */
    public void sync(UUID tenantId, long ticketId, SlaMetric metric, Instant warnAt, Instant breachAt) {
        Runnable apply = () -> {
            try {
                apply(new EscalationJob(tenantId, ticketId, metric, EscalationKind.WARNING), warnAt);
                apply(new EscalationJob(tenantId, ticketId, metric, EscalationKind.BREACH), breachAt);
            } catch (RuntimeException e) {
                log.error("Could not update escalation jobs for ticket {} {}; reconciliation will repair", ticketId, metric, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    apply.run();
                }
            });
        } else {
            apply.run();
        }
    }

    private void apply(EscalationJob job, Instant at) {
        if (at == null) {
            queue.cancel(job);
        } else {
            queue.schedule(job, at);
        }
    }
}
