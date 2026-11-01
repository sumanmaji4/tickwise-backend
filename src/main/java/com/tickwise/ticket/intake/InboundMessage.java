package com.tickwise.ticket.intake;

import com.tickwise.ticket.Channel;
import com.tickwise.ticket.NewAttachment;
import java.util.List;
import java.util.UUID;

/**
 * A customer message from any channel, normalised before threading and ticket creation. The
 * web form and the email webhook both produce one of these, so the two channels share a single
 * intake path.
 *
 * @param emailMessageId     RFC 5322 Message-ID, used for idempotency (null for the web form)
 * @param threadMessageIds   In-Reply-To followed by References, most specific first
 * @param replyTokenTicketId ticket id parsed from a {@code tenant+t123@...} recipient, or null
 */
public record InboundMessage(
        UUID tenantId,
        Channel channel,
        String fromEmail,
        String fromName,
        String subject,
        String body,
        String emailMessageId,
        List<String> threadMessageIds,
        Long replyTokenTicketId,
        List<NewAttachment> attachments) {

    public InboundMessage {
        threadMessageIds = threadMessageIds == null ? List.of() : List.copyOf(threadMessageIds);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
