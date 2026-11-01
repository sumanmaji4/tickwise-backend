package com.tickwise.notification;

import java.util.UUID;

public final class NotificationEvents {

    private NotificationEvents() {}

    /** A notification row was written; pushed to the user's WebSocket session after commit. */
    public record Created(UUID tenantId, UUID userId, NotificationView notification) {}
}
