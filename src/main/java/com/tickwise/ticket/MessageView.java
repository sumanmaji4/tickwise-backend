package com.tickwise.ticket;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MessageView(
        UUID id,
        long ticketId,
        MessageDirection direction,
        UUID authorUserId,
        String authorName,
        String body,
        List<AttachmentView> attachments,
        Instant createdAt) {}
