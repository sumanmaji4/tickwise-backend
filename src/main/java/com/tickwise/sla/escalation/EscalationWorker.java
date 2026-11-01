package com.tickwise.sla.escalation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Worker-process driver: reconciles once at start-up, then polls only the due part of the queue. */
@Component
@Profile("worker")
class EscalationWorker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EscalationWorker.class);

    private final EscalationProcessor processor;

    EscalationWorker(EscalationProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void run(ApplicationArguments args) {
        long batches = processor.reconcile();
        log.info("Escalation queue reconciled ({} batches of running SLA clocks)", batches);
    }

    @Scheduled(fixedDelayString = "${tickwise.worker.escalation-poll:PT1S}")
    void poll() {
        // Drain: keep going while full batches come back, so a backlog clears quickly.
        while (processor.processDue() == EscalationProcessor.BATCH_SIZE) {
            log.debug("Escalation batch full; polling again");
        }
    }
}
