package com.tickwise.email;

import com.tickwise.shared.domain.Times;
import com.tickwise.tenant.InvitationEvents;
import com.tickwise.tenant.InvitationLinks;
import com.tickwise.tenant.TenantService;
import org.springframework.beans.factory.annotation.Value;
import com.tickwise.tenant.TenantView;
import com.tickwise.ticket.TicketEvents;
import com.tickwise.ticket.TicketService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns ticket events into outbox rows <em>in the same transaction</em>: a reply exists exactly
 * when its email is queued. Every email carries the ticket's reply-token Reply-To and
 * {@code [#id]} in the subject, so a customer reply threads even if the provider rewrites our
 * Message-ID.
 */
@Component
class OutboxWriter {

    private final OutboundEmailRepository outbox;
    private final TicketService tickets;
    private final TenantService tenants;
    private final EmailProperties properties;
    private final String frontendUrl;
    private final Clock clock;

    OutboxWriter(OutboundEmailRepository outbox, TicketService tickets, TenantService tenants,
                 EmailProperties properties, @Value("${tickwise.frontend-url}") String frontendUrl, Clock clock) {
        this.outbox = outbox;
        this.tickets = tickets;
        this.tenants = tenants;
        this.properties = properties;
        this.frontendUrl = frontendUrl;
        this.clock = clock;
    }

    /** Invitation email with the single-use accept link; queued with the invitation itself. */
    @EventListener
    void onInvitation(InvitationEvents.Created event) {
        TenantView tenant = tenants.get(event.tenantId());
        String link = InvitationLinks.acceptUrl(frontendUrl, event.rawToken());
        String body = "Hi,\n\n" + event.inviterName() + " invited you to join " + tenant.name()
                + " on Tickwise, the support desk your team uses.\n\nAccept the invitation and choose a password:\n"
                + link + "\n\nThe link works once and expires on " + event.expiresAt() + " (UTC)."
                + "\nIf you weren't expecting this, you can ignore this email.";
        outbox.save(new OutboundEmail(event.tenantId(), null, null, event.email(), null, properties.fromAddress(),
                "You're invited to " + tenant.name() + " on Tickwise", body, newMessageId(), null, null, true,
                Times.now(clock)));
    }

    @EventListener
    void onReply(TicketEvents.ReplySent event) {
        TicketService.OutboundContext ctx = tickets.outboundContext(event.tenantId(), event.ticketId(), event.messageId());
        TenantView tenant = tenants.get(event.tenantId());
        String emailMessageId = newMessageId();
        tickets.recordEmailMessageId(event.tenantId(), event.messageId(), emailMessageId);
        String body = ctx.body() + "\n\n--\n" + tenant.name() + " Support · Ticket #" + ctx.ticketId()
                + "\nReply to this email to respond.";
        enqueue(event.tenantId(), ctx, tenant, event.messageId(), "Re: " + tagged(ctx), body, emailMessageId, false);
    }

    /** Acknowledgement for every new ticket, marked Auto-Submitted so auto-responders stay quiet. */
    @EventListener
    void onCreated(TicketEvents.Created event) {
        TicketService.OutboundContext ctx = tickets.outboundContext(event.tenantId(), event.ticketId(), null);
        TenantView tenant = tenants.get(event.tenantId());
        String greeting = ctx.customerName() == null ? "Hello," : "Hi " + ctx.customerName() + ",";
        String body = greeting + "\n\nThanks for contacting " + tenant.name() + ". We have received your request and "
                + "will get back to you soon.\n\nReference: #" + ctx.ticketId()
                + "\nReply to this email to add more information.";
        enqueue(event.tenantId(), ctx, tenant, null, tagged(ctx), body, newMessageId(), true);
    }

    private void enqueue(UUID tenantId, TicketService.OutboundContext ctx, TenantView tenant, UUID messageId,
                         String subject, String body, String emailMessageId, boolean autoSubmitted) {
        Instant now = Times.now(clock);
        String references = String.join(" ", ctx.references());
        outbox.save(new OutboundEmail(tenantId, ctx.ticketId(), messageId, ctx.customerEmail(), ctx.customerName(),
                ReplyAddress.forTicket(tenant.slug(), ctx.ticketId(), properties.inboundDomain()),
                truncate(subject, 400), body, emailMessageId, ctx.inReplyTo(), references.isBlank() ? null : references,
                autoSubmitted, now));
    }

    private static String tagged(TicketService.OutboundContext ctx) {
        String tag = "[#" + ctx.ticketId() + "]";
        return ctx.subject().contains(tag) ? ctx.subject() : tag + " " + ctx.subject();
    }

    private String newMessageId() {
        return "<" + UUID.randomUUID() + "@" + properties.fromDomain() + ">";
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
