package com.tickwise.sla.escalation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Delay queue on a Redis sorted set: member = {@link EscalationJob#key()}, score = due epoch
 * millis. The worker only ever reads the due prefix ({@code ZRANGEBYSCORE -inf now}), so its cost
 * is proportional to the number of due jobs, never to the number of open tickets.
 *
 * <p>Claiming moves each due job's score forward to a lease deadline in the same Lua script, so
 * several workers never claim the same job, and a worker that crashes mid-job only delays it.
 * Completing removes the job only if its score still equals the lease. If the clock changed and
 * the job was re-scheduled meanwhile, the new schedule survives.
 */
@Component
class RedisEscalationQueue implements EscalationQueue {

    static final String KEY = "tickwise:escalations";
    static final String FAILURES_KEY = "tickwise:escalations:failures";

    private static final RedisScript<List> CLAIM = RedisScript.of("""
            local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[2]))
            for _, member in ipairs(due) do
              redis.call('ZADD', KEYS[1], 'XX', ARGV[3], member)
            end
            return due
            """, List.class);

    private static final RedisScript<Long> COMPLETE = RedisScript.of("""
            local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
            redis.call('HDEL', KEYS[2], ARGV[1])
            if score and tonumber(score) == tonumber(ARGV[2]) then
              return redis.call('ZREM', KEYS[1], ARGV[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    RedisEscalationQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void schedule(EscalationJob job, Instant dueAt) {
        redis.opsForZSet().add(KEY, job.key(), dueAt.toEpochMilli());
    }

    @Override
    public void cancel(EscalationJob job) {
        redis.opsForZSet().remove(KEY, job.key());
        redis.opsForHash().delete(FAILURES_KEY, job.key());
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Claimed> claimDue(Instant now, int limit, Duration lease) {
        long leaseScore = now.plus(lease).toEpochMilli();
        List<String> keys = redis.execute(CLAIM, List.of(KEY),
                String.valueOf(now.toEpochMilli()), String.valueOf(limit), String.valueOf(leaseScore));
        if (keys == null) {
            return List.of();
        }
        return keys.stream().map(k -> new Claimed(EscalationJob.parse(k), leaseScore)).toList();
    }

    @Override
    public void complete(Claimed claimed) {
        redis.execute(COMPLETE, List.of(KEY, FAILURES_KEY), claimed.job().key(), String.valueOf(claimed.leaseScore()));
    }

    /** Counts a failed attempt and returns the total so far. */
    long recordFailure(EscalationJob job) {
        return redis.opsForHash().increment(FAILURES_KEY, job.key(), 1);
    }

    /** Due time of a scheduled job, for tests and diagnostics. */
    Instant scheduledAt(EscalationJob job) {
        Double score = redis.opsForZSet().score(KEY, job.key());
        return score == null ? null : Instant.ofEpochMilli(score.longValue());
    }
}
