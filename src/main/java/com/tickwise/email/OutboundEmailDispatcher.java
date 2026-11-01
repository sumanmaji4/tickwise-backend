package com.tickwise.email;

import com.tickwise.shared.domain.Times;
import com.tickwise.tenant.TenantService;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers queued emails one row at a time with {@code FOR UPDATE SKIP LOCKED}. Delivery is
 * at-least-once: a crash after the provider accepted a message but before the row is marked
 * SENT re-sends it. The stable Message-ID lets receiving systems de-duplicate.
 */
@Component
public class OutboundEmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboundEmailDispatcher.class);

    private final OutboundEmailRepository outbox;
    private final EmailSender sender;
    private final TenantService tenants;
    private final EmailProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    OutboundEmailDispatcher(OutboundEmailRepository outbox, EmailSender sender, TenantService tenants,
                            EmailProperties properties, PlatformTransactionManager txManager, Clock clock) {
        this.outbox = outbox;
        this.sender = sender;
        this.tenants = tenants;
        this.properties = properties;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public int dispatchBatch(int max) {
        int handled = 0;
        while (handled < max && Boolean.TRUE.equals(tx.execute(status -> dispatchOne()))) {
            handled++;
        }
        return handled;
    }

    private boolean dispatchOne() {
        Optional<OutboundEmail> next = outbox.claimNext(Times.now(clock));
        if (next.isEmpty()) {
            return false;
        }
        OutboundEmail email = next.get();
        String fromName = tenants.get(email.getTenantId()).name() + " Support";
        try {
            sender.send(email.toOutgoing(properties.fromAddress(), fromName));
            email.markSent(Times.now(clock));
        } catch (RuntimeException e) {
            email.failAttempt(e.toString(), Times.now(clock));
            log.warn("Sending email {} failed (attempt {}): {}", email.getId(), email.getAttempts(), e.toString());
        }
        return true;
    }
}
