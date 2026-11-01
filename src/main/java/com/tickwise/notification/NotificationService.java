package com.tickwise.notification;

import com.tickwise.shared.domain.Times;
import com.tickwise.shared.error.NotFoundException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** In-app notifications. Every read and write is scoped to the caller's tenant and user id. */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    NotificationService(NotificationRepository notifications, ApplicationEventPublisher events, Clock clock) {
        this.notifications = notifications;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    void notify(UUID tenantId, UUID userId, Notification.Type type, Long ticketId, String message) {
        Notification n = notifications.save(new Notification(tenantId, userId, type, ticketId, message, Times.now(clock)));
        events.publishEvent(new NotificationEvents.Created(tenantId, userId, n.toView()));
    }

    @Transactional(readOnly = true)
    public List<NotificationView> list(UUID tenantId, UUID userId, boolean unreadOnly, int limit) {
        PageRequest page = PageRequest.of(0, limit);
        var rows = unreadOnly
                ? notifications.findAllByTenantIdAndUserIdAndReadAtIsNullOrderByCreatedAtDesc(tenantId, userId, page)
                : notifications.findAllByTenantIdAndUserIdOrderByCreatedAtDesc(tenantId, userId, page);
        return rows.stream().map(Notification::toView).toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID tenantId, UUID userId) {
        return notifications.countByTenantIdAndUserIdAndReadAtIsNull(tenantId, userId);
    }

    @Transactional
    public void markRead(UUID tenantId, UUID userId, UUID notificationId) {
        notifications.findByTenantIdAndUserIdAndId(tenantId, userId, notificationId)
                .orElseThrow(() -> new NotFoundException("Notification", notificationId))
                .markRead(Times.now(clock));
    }

    @Transactional
    public int markAllRead(UUID tenantId, UUID userId) {
        return notifications.markAllRead(tenantId, userId, Times.now(clock));
    }
}
