package com.tickwise.notification.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/** Each API instance relays Redis pub/sub messages to its own STOMP sessions. */
@Configuration(proxyBeanMethods = false)
@Profile("!worker")
class RealtimeRelay {

    private static final Logger log = LoggerFactory.getLogger(RealtimeRelay.class);

    @Bean
    RedisMessageListenerContainer realtimeListener(RedisConnectionFactory redis, SimpMessagingTemplate stomp,
                                                   ObjectMapper json) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redis);
        container.addMessageListener((message, pattern) -> {
            try {
                RealtimeMessage m = json.readValue(new String(message.getBody(), StandardCharsets.UTF_8), RealtimeMessage.class);
                Map<String, Object> body = new LinkedHashMap<>(m.payload());
                body.put("type", m.type());
                if (m.userId() == null) {
                    stomp.convertAndSend(RealtimeMessage.tenantTopic(m.tenantId()), body);
                } else {
                    stomp.convertAndSendToUser(m.userId().toString(), RealtimeMessage.USER_QUEUE, body);
                }
            } catch (Exception e) {
                log.warn("Dropping malformed real-time message: {}", e.getMessage());
            }
        }, new ChannelTopic(RealtimePublisher.CHANNEL));
        return container;
    }
}
