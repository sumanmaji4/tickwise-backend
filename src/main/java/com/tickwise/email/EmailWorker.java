package com.tickwise.email;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Worker-process loops for the inbound queue and the outbound outbox. */
@Component
@Profile("worker")
class EmailWorker {

    private static final int BATCH = 50;

    private final InboundEmailProcessor inbound;
    private final OutboundEmailDispatcher outbound;

    EmailWorker(InboundEmailProcessor inbound, OutboundEmailDispatcher outbound) {
        this.inbound = inbound;
        this.outbound = outbound;
    }

    @Scheduled(fixedDelayString = "${tickwise.worker.inbound-poll:PT1S}")
    void inbound() {
        while (inbound.processBatch(BATCH) == BATCH) {
            // keep draining a backlog
        }
    }

    @Scheduled(fixedDelayString = "${tickwise.worker.outbound-poll:PT2S}")
    void outbound() {
        while (outbound.dispatchBatch(BATCH) == BATCH) {
            // keep draining a backlog
        }
    }
}
