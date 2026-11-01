package com.tickwise.shared.web;

import com.tickwise.shared.error.TooManyRequestsException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counters in Redis, shared by every API instance. A window is identified by
 * {@code key + window index}; INCR and the first EXPIRE run in one Lua script, so a counter can
 * never be left without a TTL.
 */
@Component
public class RateLimiter {

    private static final RedisScript<Long> INCREMENT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    RateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** @throws TooManyRequestsException when more than {@code limit} calls hit {@code key} in the current window */
    public void check(String key, int limit, Duration window) {
        long windowMs = window.toMillis();
        long nowMs = clock.millis();
        long index = nowMs / windowMs;
        Long count = redis.execute(INCREMENT, List.of("tickwise:ratelimit:" + key + ":" + index), String.valueOf(windowMs));
        if (count != null && count > limit) {
            throw new TooManyRequestsException(Duration.ofMillis((index + 1) * windowMs - nowMs));
        }
    }
}
