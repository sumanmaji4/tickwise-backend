package com.tickwise.notification.realtime;

import java.util.Map;
import java.util.UUID;

/**
 * Envelope relayed between processes over Redis pub/sub. {@code userId == null} means the
 * tenant's ticket topic; otherwise it is that user's private queue.
 */
record RealtimeMessage(UUID tenantId, UUID userId, String type, Map<String, Object> payload) {

    static String tenantTopic(UUID tenantId) {
        return "/topic/tenants/" + tenantId + "/tickets";
    }

    static final String USER_QUEUE = "/queue/notifications";
}
