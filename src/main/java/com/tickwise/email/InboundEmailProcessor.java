package com.tickwise.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickwise.shared.domain.Times;
import com.tickwise.shared.storage.ObjectStorage;
import com.tickwise.tenant.TenantService;
import com.tickwise.tenant.TenantView;
import com.tickwise.ticket.Channel;
import com.tickwise.ticket.NewAttachment;
import com.tickwise.ticket.TicketService;
import com.tickwise.ticket.intake.InboundMessage;
import com.tickwise.ticket.intake.IntakeService;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains the inbound email queue. Each email is processed in its own transaction while its row
 * is locked. On failure the transaction rolls back, and a separate transaction records the
 * attempt with backoff, so one bad email never blocks the queue.
 */
@Component
public class InboundEmailProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboundEmailProcessor.class);

    private final InboundEmailRepository inbox;
    private final TransactionTemplate tx;
    private final TenantService tenants;
    private final TicketService tickets;
    private final IntakeService intake;
    private final ObjectStorage storage;
    private final AttachmentFetcher fetcher;
    private final EmailProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    InboundEmailProcessor(InboundEmailRepository inbox, PlatformTransactionManager txManager, TenantService tenants,
                          TicketService tickets, IntakeService intake, ObjectStorage storage, AttachmentFetcher fetcher,
                          EmailProperties properties, ObjectMapper json, Clock clock) {
        this.inbox = inbox;
        this.tx = new TransactionTemplate(txManager);
        this.tenants = tenants;
        this.tickets = tickets;
        this.intake = intake;
        this.storage = storage;
        this.fetcher = fetcher;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    /** Processes up to {@code max} due emails; returns how many were handled. */
    public int processBatch(int max) {
        int handled = 0;
        while (handled < max && processOne()) {
            handled++;
        }
        return handled;
    }

    private boolean processOne() {
        AtomicReference<Long> claimed = new AtomicReference<>();
        try {
            return Boolean.TRUE.equals(tx.execute(status -> {
                Optional<InboundEmail> next = inbox.claimNext(Times.now(clock));
                if (next.isEmpty()) {
                    return false;
                }
                claimed.set(next.get().getId());
                handle(next.get());
                return true;
            }));
        } catch (RuntimeException e) {
            if (claimed.get() == null) {
                throw e;
            }
            log.warn("Inbound email {} failed: {}", claimed.get(), e.toString());
            tx.executeWithoutResult(status -> inbox.findById(claimed.get())
                    .ifPresent(email -> email.failAttempt(e.toString(), Times.now(clock))));
            return true;
        }
    }

    private void handle(InboundEmail email) {
        Instant now = Times.now(clock);
        ParsedEmail parsed;
        try {
            parsed = BrevoInboundMapper.map(json.readTree(email.getPayload()));
        } catch (IOException e) {
            email.finish(InboundEmail.Status.IGNORED, null, null, "unparseable payload", now);
            return;
        }
        if (parsed.fromAddress() == null || parsed.fromAddress().isBlank()) {
            email.finish(InboundEmail.Status.IGNORED, null, null, "no sender address", now);
            return;
        }
        if (parsed.fromAddress().equalsIgnoreCase(properties.fromAddress())) {
            email.finish(InboundEmail.Status.IGNORED, null, null, "sent by Tickwise itself (loop)", now);
            return;
        }
        Optional<String> automated = AutoReplyDetector.detect(parsed.headers(), parsed.fromAddress(), parsed.subject());
        if (automated.isPresent()) {
            email.finish(InboundEmail.Status.IGNORED, null, null, "automated: " + automated.get(), now);
            return;
        }
        Optional<Recipient> recipient = resolveRecipient(parsed.recipients());
        if (recipient.isEmpty()) {
            email.finish(InboundEmail.Status.IGNORED, null, null, "no recipient matches a workspace", now);
            return;
        }
        UUID tenantId = recipient.get().tenant().id();
        if (parsed.messageId() != null && tickets.emailAlreadyReceived(tenantId, parsed.messageId())) {
            email.finish(InboundEmail.Status.DUPLICATE, tenantId, null, "Message-ID already received", now);
            return;
        }
        List<String> notes = new ArrayList<>();
        List<NewAttachment> files = storeAttachments(tenantId, parsed.attachments(), notes);
        String body = notes.isEmpty() ? parsed.body() : parsed.body() + "\n\n" + String.join("\n", notes);

        IntakeService.Result result = intake.receive(new InboundMessage(tenantId, Channel.EMAIL, parsed.fromAddress(),
                parsed.fromName(), parsed.subject(), body.isBlank() ? "(empty message)" : body, parsed.messageId(),
                parsed.threadMessageIds(), recipient.get().ticketId(), files));
        InboundEmail.Status status = result.outcome() == IntakeService.Outcome.DUPLICATE
                ? InboundEmail.Status.DUPLICATE : InboundEmail.Status.PROCESSED;
        email.finish(status, tenantId, result.ticketId(),
                result.outcome() + (result.threadedBy() == null ? "" : " via " + result.threadedBy()), now);
    }

    private record Recipient(TenantView tenant, Long ticketId) {}

    private Optional<Recipient> resolveRecipient(List<String> addresses) {
        for (String address : addresses) {
            Optional<ReplyAddress.Parsed> parsed = ReplyAddress.parse(address, properties.inboundDomain());
            if (parsed.isPresent()) {
                Optional<TenantView> tenant = tenants.findBySlug(parsed.get().tenantSlug());
                if (tenant.isPresent()) {
                    return Optional.of(new Recipient(tenant.get(), parsed.get().ticketId()));
                }
            }
        }
        return Optional.empty();
    }

    private List<NewAttachment> storeAttachments(UUID tenantId, List<ParsedEmail.Attachment> attachments, List<String> notes) {
        long maxBytes = properties.maxAttachmentSize().toBytes();
        List<NewAttachment> stored = new ArrayList<>();
        for (ParsedEmail.Attachment a : attachments) {
            String filename = safeFilename(a.filename());
            byte[] content;
            if (a.base64Content() != null) {
                content = Base64.getMimeDecoder().decode(a.base64Content());
            } else if (a.downloadToken() != null && fetcher.supported()) {
                if (a.size() > maxBytes) {
                    notes.add("[attachment " + filename + " omitted: larger than " + properties.maxAttachmentSize() + "]");
                    continue;
                }
                content = fetcher.fetch(a.downloadToken());
            } else {
                notes.add("[attachment " + filename + " could not be retrieved]");
                continue;
            }
            if (content == null || content.length > maxBytes) {
                notes.add("[attachment " + filename + " omitted: larger than " + properties.maxAttachmentSize() + "]");
                continue;
            }
            UUID id = UUID.randomUUID();
            String key = tenantId + "/attachments/" + id;
            String contentType = a.contentType() == null || a.contentType().isBlank() ? "application/octet-stream" : a.contentType();
            storage.put(key, content, contentType);
            stored.add(new NewAttachment(id, filename, contentType, content.length, key));
        }
        return stored;
    }

    static String safeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "attachment";
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "_").strip();
        if (base.isEmpty() || base.equals(".") || base.equals("..")) {
            return "attachment";
        }
        return base.length() > 255 ? base.substring(base.length() - 255) : base;
    }
}
