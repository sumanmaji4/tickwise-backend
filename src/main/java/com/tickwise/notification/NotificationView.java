package com.tickwise.notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationView(UUID id, String type, Long ticketId, String message, Instant createdAt, Instant readAt) {}
