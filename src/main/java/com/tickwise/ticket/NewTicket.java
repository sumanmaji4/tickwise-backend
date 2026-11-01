package com.tickwise.ticket;

import com.tickwise.shared.domain.Priority;
import java.util.List;
import java.util.UUID;

/**
 * A new ticket; the first message is the customer's request (its description).
 *
 * @param teamId    optional team to route to (null: the tenant's default routing applies)
 * @param createdBy the agent who opened it on the customer's behalf, or null when the customer did
 */
public record NewTicket(
        Channel channel,
        String customerEmail,
        String customerName,
        String subject,
        String body,
        Priority priority,
        String emailMessageId,
        List<NewAttachment> attachments,
        UUID teamId,
        UUID createdBy) {

    public NewTicket {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** A ticket opened by the customer (web form or email). */
    public NewTicket(Channel channel, String customerEmail, String customerName, String subject, String body,
                     Priority priority, String emailMessageId, List<NewAttachment> attachments) {
        this(channel, customerEmail, customerName, subject, body, priority, emailMessageId, attachments, null, null);
    }
}
