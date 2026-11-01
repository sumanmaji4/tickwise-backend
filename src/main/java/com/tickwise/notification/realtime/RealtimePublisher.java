package com.tickwise.notification.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes to Redis rather than straight to the STOMP broker: the event may come from the
 * worker process (an SLA breach, an inbound email), while WebSocket sessions live in API
 * instances. Every API instance subscribes and relays to its own clients (see {@link RealtimeRelay}).
 * Best effort: the dashboard also refetches on reconnect, so a lost push is never data loss.
 */
@Component
class RealtimePublisher {

    static final String CHANNEL = "tickwise:realtime";

    private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    RealtimePublisher(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    void publish(RealtimeMessage message) {
        try {
            redis.convertAndSend(CHANNEL, json.writeValueAsString(message));
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Real-time publish failed ({}): {}", message.type(), e.getMessage());
        }
    }
}
