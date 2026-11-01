package com.tickwise.sla.escalation;

import static org.assertj.core.api.Assertions.assertThat;

import com.tickwise.sla.SlaMetric;
import com.tickwise.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Semantics of the sorted-set delay queue against a real Redis. */
class RedisEscalationQueueIT extends IntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(30);

    @Autowired RedisEscalationQueue queue;
    @Autowired StringRedisTemplate redis;

    private final Instant now = Instant.parse("2026-03-02T12:00:00Z");

    @BeforeEach
    void clear() {
        redis.delete(RedisEscalationQueue.KEY);
    }

    private static EscalationJob job(long ticket) {
        return new EscalationJob(UUID.randomUUID(), ticket, SlaMetric.RESOLUTION, EscalationKind.BREACH);
    }

    @Test
    void onlyDueJobsAreClaimedInDueOrder() {
        EscalationJob later = job(1);
        EscalationJob sooner = job(2);
        EscalationJob future = job(3);
        queue.schedule(later, now.minusSeconds(10));
        queue.schedule(sooner, now.minusSeconds(60));
        queue.schedule(future, now.plusSeconds(60));

        List<EscalationQueue.Claimed> claimed = queue.claimDue(now, 10, LEASE);

        assertThat(claimed).extracting(EscalationQueue.Claimed::job).containsExactly(sooner, later);
    }

    @Test
    void reschedulingReplacesInsteadOfDuplicating() {
        EscalationJob j = job(1);
        queue.schedule(j, now.minusSeconds(5));
        queue.schedule(j, now.plusSeconds(600));

        assertThat(queue.claimDue(now, 10, LEASE)).isEmpty();
        assertThat(redis.opsForZSet().size(RedisEscalationQueue.KEY)).isEqualTo(1);
    }

    @Test
    void claimedJobIsHiddenUntilItsLeaseExpiresThenRedelivered() {
        EscalationJob j = job(1);
        queue.schedule(j, now);

        assertThat(queue.claimDue(now, 10, LEASE)).hasSize(1);
        assertThat(queue.claimDue(now.plusSeconds(1), 10, LEASE)).isEmpty();
        assertThat(queue.claimDue(now.plus(LEASE), 10, LEASE)).hasSize(1);
    }

    @Test
    void completeRemovesTheJob() {
        EscalationJob j = job(1);
        queue.schedule(j, now);
        EscalationQueue.Claimed claimed = queue.claimDue(now, 10, LEASE).getFirst();

        queue.complete(claimed);

        assertThat(queue.scheduledAt(j)).isNull();
    }

    @Test
    void completeDoesNotDeleteAJobRescheduledWhileItWasBeingProcessed() {
        EscalationJob j = job(1);
        queue.schedule(j, now);
        EscalationQueue.Claimed claimed = queue.claimDue(now, 10, LEASE).getFirst();
        Instant newDue = now.plus(Duration.ofHours(2));
        queue.schedule(j, newDue); // e.g. the ticket was re-prioritised meanwhile

        queue.complete(claimed);

        assertThat(queue.scheduledAt(j)).isEqualTo(newDue);
    }

    @Test
    void cancelRemovesTheJob() {
        EscalationJob j = job(1);
        queue.schedule(j, now);
        queue.cancel(j);
        assertThat(queue.claimDue(now, 10, LEASE)).isEmpty();
    }

    @Test
    void keysRoundTrip() {
        EscalationJob j = new EscalationJob(UUID.randomUUID(), 42, SlaMetric.FIRST_RESPONSE, EscalationKind.WARNING);
        assertThat(EscalationJob.parse(j.key())).isEqualTo(j);
    }
}
