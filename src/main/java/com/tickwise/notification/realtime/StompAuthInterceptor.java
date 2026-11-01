package com.tickwise.notification.realtime;

import com.tickwise.auth.AccessTokenVerifier;
import com.tickwise.shared.security.AuthenticatedUser;
import com.tickwise.shared.security.Role;
import java.security.Principal;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.AuthenticationException;

/**
 * Authenticates the STOMP CONNECT frame with the same JWT as the REST API, and enforces tenant
 * scoping on SUBSCRIBE: a client may only subscribe to its own tenant's ticket topic and to its
 * private notification queue. Client SEND frames are rejected.
 */
class StompAuthInterceptor implements ChannelInterceptor {

    private final AccessTokenVerifier tokens;

    StompAuthInterceptor(AccessTokenVerifier tokens) {
        this.tokens = tokens;
    }

    record StompPrincipal(AuthenticatedUser user) implements Principal {
        @Override
        public String getName() {
            return user.userId().toString();
        }
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        switch (accessor.getCommand()) {
            case CONNECT -> {
                try {
                    AuthenticatedUser user = tokens.verify(accessor.getFirstNativeHeader("Authorization"));
                    if (user.role() == Role.CUSTOMER) {
                        throw new MessageDeliveryException("Real-time updates are for staff only");
                    }
                    accessor.setUser(new StompPrincipal(user));
                } catch (AuthenticationException e) {
                    throw new MessageDeliveryException("Unauthorized: " + e.getMessage());
                }
            }
            case SUBSCRIBE -> {
                AuthenticatedUser user = userOf(accessor);
                String destination = accessor.getDestination();
                boolean ownTopic = RealtimeMessage.tenantTopic(user.tenantId()).equals(destination);
                boolean ownQueue = ("/user" + RealtimeMessage.USER_QUEUE).equals(destination);
                if (!ownTopic && !ownQueue) {
                    throw new MessageDeliveryException("Forbidden destination: " + destination);
                }
            }
            case SEND -> throw new MessageDeliveryException("Clients cannot send messages");
            default -> {
                // DISCONNECT, UNSUBSCRIBE, heartbeats: nothing to check
            }
        }
        return message;
    }

    private static AuthenticatedUser userOf(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof StompPrincipal p) {
            return p.user();
        }
        throw new MessageDeliveryException("Not authenticated");
    }
}
