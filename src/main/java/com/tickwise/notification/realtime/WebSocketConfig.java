package com.tickwise.notification.realtime;

import com.tickwise.auth.AccessTokenVerifier;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over a plain WebSocket at {@code /ws}. The in-memory simple broker is enough because
 * fan-out across API instances happens through Redis ({@link RealtimeRelay}): each instance
 * delivers only to its own sessions. API process only; the worker has no WebSocket clients.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
@Profile("!worker")
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AccessTokenVerifier tokens;
    private final List<String> allowedOrigins;

    WebSocketConfig(AccessTokenVerifier tokens,
                    @Value("${tickwise.security.cors-allowed-origins}") List<String> allowedOrigins) {
        this.tokens = tokens;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setUserDestinationPrefix("/user");
        // No application destinations: clients only subscribe; all writes go through REST.
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompAuthInterceptor(tokens));
    }
}
