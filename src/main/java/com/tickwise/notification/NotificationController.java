package com.tickwise.notification;

import com.tickwise.shared.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications")
class NotificationController {

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @Operation(summary = "The caller's notifications, newest first")
    List<NotificationView> list(@AuthenticationPrincipal AuthenticatedUser caller,
                                @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return notifications.list(caller.tenantId(), caller.userId(), unreadOnly, 50);
    }

    @GetMapping("/unread-count")
    Map<String, Long> unreadCount(@AuthenticationPrincipal AuthenticatedUser caller) {
        return Map.of("unread", notifications.unreadCount(caller.tenantId(), caller.userId()));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markRead(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID id) {
        notifications.markRead(caller.tenantId(), caller.userId(), id);
    }

    @PostMapping("/read-all")
    Map<String, Integer> markAllRead(@AuthenticationPrincipal AuthenticatedUser caller) {
        return Map.of("updated", notifications.markAllRead(caller.tenantId(), caller.userId()));
    }
}
