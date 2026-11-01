package com.tickwise.ticket;

import com.tickwise.shared.domain.Priority;
import com.tickwise.shared.domain.Times;
import com.tickwise.shared.error.ConflictException;
import com.tickwise.shared.error.NotFoundException;
import com.tickwise.shared.error.StaleVersionException;
import com.tickwise.sla.SlaService;
import com.tickwise.sla.SlaSnapshot;
import com.tickwise.tenant.TeamService;
import com.tickwise.tenant.UserService;
import com.tickwise.tenant.UserView;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ticket aggregate's public API. Every method takes the tenant id and scopes all reads by it.
 * SLA clock changes go through {@link SlaService} in the same transaction, and every status,
 * priority and assignment change is written to the append-only audit log.
 */
@Service
public class TicketService {

    private final TicketRepository tickets;
    private final CustomerRepository customers;
    private final MessageRepository messages;
    private final AttachmentRepository attachments;
    private final AuditRepository audit;
    private final SlaService sla;
    private final UserService users;
    private final TeamService teams;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    TicketService(TicketRepository tickets, CustomerRepository customers, MessageRepository messages,
                  AttachmentRepository attachments, AuditRepository audit, SlaService sla, UserService users,
                  TeamService teams, ApplicationEventPublisher events, Clock clock) {
        this.teams = teams;
        this.tickets = tickets;
        this.customers = customers;
        this.messages = messages;
        this.attachments = attachments;
        this.audit = audit;
        this.sla = sla;
        this.users = users;
        this.events = events;
        this.clock = clock;
    }

    // ---- commands -------------------------------------------------------------------------

    @Transactional
    public TicketView create(UUID tenantId, NewTicket input) {
        Instant now = Times.now(clock);
        if (input.teamId() != null) {
            teams.get(tenantId, input.teamId()); // 404 for an unknown or foreign team
        }
        Customer customer = findOrCreateCustomer(tenantId, input.customerEmail(), input.customerName(), now);
        Ticket ticket = tickets.save(new Ticket(tenantId, input.subject().strip(), input.priority(), input.channel(),
                customer.getId(), input.teamId(), now));
        Message first = messages.save(Message.fromCustomer(tenantId, ticket.getId(), customer.getId(), input.body(),
                input.emailMessageId(), now));
        saveAttachments(tenantId, first.getId(), input.attachments(), now);
        ticket.applySla(sla.start(tenantId, ticket.getId(), ticket.getPriority(), now));
        record(ticket, input.createdBy(), AuditType.CREATED, now, "channel", input.channel(), "priority", input.priority());
        events.publishEvent(new TicketEvents.Created(tenantId, ticket.getId(), ticket.getChannel(), ticket.getPriority(),
                ticket.getTeamId(), customer.getEmail(), now));
        return toView(ticket);
    }

    /**
     * A customer message on an existing ticket. A ticket waiting on the customer resumes its SLA
     * clocks; a resolved ticket is reopened. Closed tickets are final: intake opens a new ticket.
     */
    @Transactional
    public MessageView addCustomerMessage(UUID tenantId, long ticketId, String body, String emailMessageId,
                                          List<NewAttachment> files) {
        Instant now = Times.now(clock);
        Ticket ticket = load(tenantId, ticketId);
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new ConflictException("Ticket #" + ticketId + " is closed");
        }
        Message message = messages.save(Message.fromCustomer(tenantId, ticketId, ticket.getCustomerId(), body,
                emailMessageId, now));
        saveAttachments(tenantId, message.getId(), files, now);
        ticket.touch(now);
        record(ticket, null, AuditType.MESSAGE_RECEIVED, now, "messageId", message.getId());
        if (ticket.getStatus() == TicketStatus.PENDING_CUSTOMER || ticket.getStatus() == TicketStatus.RESOLVED) {
            transition(ticket, TicketStatus.OPEN, null, now);
        }
        events.publishEvent(new TicketEvents.MessageReceived(tenantId, ticketId, message.getId(), now));
        return toMessageViews(tenantId, List.of(message)).getFirst();
    }

    /**
     * An agent reply to the customer. The first one meets the first-response SLA. The agent may
     * move the ticket on in the same step (typically to PENDING_CUSTOMER or RESOLVED).
     */
    @Transactional
    public MessageView reply(UUID tenantId, long ticketId, UUID agentId, String body, TicketStatus thenStatus) {
        Instant now = Times.now(clock);
        Ticket ticket = load(tenantId, ticketId);
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new ConflictException("Ticket #" + ticketId + " is closed; replies are not possible");
        }
        Message message = messages.save(Message.fromAgent(tenantId, ticketId, agentId, body, now));
        if (ticket.recordAgentReply(now)) {
            ticket.applySla(sla.firstResponseSent(tenantId, ticketId, now));
        }
        record(ticket, agentId, AuditType.REPLY_SENT, now, "messageId", message.getId());
        if (ticket.getStatus() == TicketStatus.NEW) {
            transition(ticket, TicketStatus.OPEN, agentId, now);
        }
        if (thenStatus != null && thenStatus != ticket.getStatus()) {
            transition(ticket, thenStatus, agentId, now);
        }
        events.publishEvent(new TicketEvents.ReplySent(tenantId, ticketId, message.getId(), agentId, now));
        return toMessageViews(tenantId, List.of(message)).getFirst();
    }

    /** Status and/or priority change by an agent, guarded by the version the agent last saw. */
    @Transactional
    public TicketView update(UUID tenantId, long ticketId, long expectedVersion, TicketStatus status,
                             Priority priority, UUID actorId) {
        Instant now = Times.now(clock);
        Ticket ticket = load(tenantId, ticketId);
        requireVersion(ticket, expectedVersion);
        if (priority != null && priority != ticket.getPriority()) {
            Priority from = ticket.getPriority();
            ticket.changePriority(priority, now);
            ticket.applySla(sla.priorityChanged(tenantId, ticketId, priority, now));
            record(ticket, actorId, AuditType.PRIORITY_CHANGED, now, "from", from, "to", priority);
            events.publishEvent(new TicketEvents.PriorityChanged(tenantId, ticketId, from, priority, actorId, now));
        }
        if (status != null && status != ticket.getStatus()) {
            transition(ticket, status, actorId, now);
        }
        return toView(tickets.saveAndFlush(ticket));
    }

    /**
     * Sets the assignee (and optionally the team). Called by the assignment module, which owns
     * eligibility and capacity rules. The write is flushed immediately so a concurrent assignment
     * of the same ticket fails here with an optimistic-lock conflict (see ADR 0004).
     *
     * @param expectedVersion   the version the caller saw, or null to skip the client-side check
     * @param onlyIfUnassigned  fail with 409 if someone already owns the ticket (used by "claim")
     */
    @Transactional
    public TicketView assign(UUID tenantId, long ticketId, UUID assigneeId, UUID teamId, Long expectedVersion,
                             boolean onlyIfUnassigned, UUID actorId) {
        Instant now = Times.now(clock);
        Ticket ticket = load(tenantId, ticketId);
        if (expectedVersion != null) {
            requireVersion(ticket, expectedVersion);
        }
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new ConflictException("Ticket #" + ticketId + " is closed");
        }
        if (onlyIfUnassigned && ticket.getAssigneeId() != null) {
            throw new ConflictException("Ticket #" + ticketId + " is already assigned");
        }
        UUID previous = ticket.getAssigneeId();
        boolean teamChanges = teamId != null && !teamId.equals(ticket.getTeamId());
        if (Objects.equals(previous, assigneeId) && !teamChanges) {
            return toView(ticket);
        }
        ticket.assign(assigneeId, teamId, now);
        tickets.saveAndFlush(ticket);
        AuditType type = assigneeId == null && previous != null ? AuditType.UNASSIGNED : AuditType.ASSIGNED;
        record(ticket, actorId, type, now,
                "assigneeId", assigneeId, "previousAssigneeId", previous, "teamId", ticket.getTeamId());
        events.publishEvent(new TicketEvents.Assigned(tenantId, ticketId, assigneeId, previous, ticket.getTeamId(),
                actorId, now));
        return toView(ticket);
    }

    /**
     * Soft delete (admins only, enforced by the controller). SLA clocks are stopped so no
     * escalation fires for it; the audit trail keeps a DELETED entry; customer mail that would
     * have threaded onto it opens a new ticket instead.
     */
    @Transactional
    public void delete(UUID tenantId, long ticketId, Long expectedVersion, UUID actorId) {
        Instant now = Times.now(clock);
        Ticket ticket = load(tenantId, ticketId);
        if (expectedVersion != null) {
            requireVersion(ticket, expectedVersion);
        }
        sla.stopForDeletion(tenantId, ticketId, now);
        record(ticket, actorId, AuditType.DELETED, now, "status", ticket.getStatus(), "subject", ticket.getSubject());
        ticket.markDeleted(actorId, now);
        tickets.saveAndFlush(ticket);
        events.publishEvent(new TicketEvents.Deleted(tenantId, ticketId, actorId, now));
    }

    /** Remembers the RFC 5322 Message-ID given to an outbound reply, for In-Reply-To threading. */
    @Transactional
    public void recordEmailMessageId(UUID tenantId, UUID messageId, String emailMessageId) {
        messages.findByTenantIdAndId(tenantId, messageId)
                .orElseThrow(() -> new NotFoundException("Message", messageId))
                .assignEmailMessageId(emailMessageId);
    }

    // ---- queries --------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public TicketView get(UUID tenantId, long ticketId) {
        return toView(load(tenantId, ticketId));
    }

    @Transactional(readOnly = true)
    public Optional<TicketView> find(UUID tenantId, long ticketId) {
        return tickets.findByTenantIdAndId(tenantId, ticketId).map(this::toView);
    }

    @Transactional(readOnly = true)
    public TicketDetail detail(UUID tenantId, long ticketId) {
        Ticket ticket = load(tenantId, ticketId);
        SlaSnapshot snapshot = sla.snapshot(tenantId, ticketId, Times.now(clock));
        return new TicketDetail(toView(ticket), snapshot);
    }

    @Transactional(readOnly = true)
    public Page<TicketView> search(UUID tenantId, TicketQuery query, Pageable page) {
        Page<Ticket> result = tickets.findAll(TicketSpecifications.matching(tenantId, query), page);
        return new PageImpl<>(toViews(tenantId, result.getContent()), page, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<MessageView> messages(UUID tenantId, long ticketId) {
        load(tenantId, ticketId);
        return toMessageViews(tenantId, messages.findAllByTenantIdAndTicketIdOrderByCreatedAtAsc(tenantId, ticketId));
    }

    @Transactional(readOnly = true)
    public List<AuditEntryView> auditTrail(UUID tenantId, long ticketId) {
        load(tenantId, ticketId);
        return audit.findAllByTenantIdAndTicketIdOrderByIdAsc(tenantId, ticketId).stream().map(AuditEntry::toView).toList();
    }

    /** Active (NEW, OPEN, PENDING_CUSTOMER) tickets per assignee; agents with none are absent. */
    @Transactional(readOnly = true)
    public Map<UUID, Long> openTicketCounts(UUID tenantId, Collection<UUID> assigneeIds) {
        if (assigneeIds.isEmpty()) {
            return Map.of();
        }
        return tickets.countByAssignee(tenantId, assigneeIds, TicketStatus.ACTIVE).stream()
                .collect(Collectors.toMap(TicketRepository.AssigneeLoad::getAssigneeId,
                        TicketRepository.AssigneeLoad::getOpenCount));
    }

    @Transactional(readOnly = true)
    public boolean emailAlreadyReceived(UUID tenantId, String emailMessageId) {
        return messages.existsByTenantIdAndEmailMessageId(tenantId, emailMessageId);
    }

    /** The most recent ticket containing any of the given Message-IDs (In-Reply-To / References). */
    @Transactional(readOnly = true)
    public Optional<Long> ticketIdForEmailMessageIds(UUID tenantId, Collection<String> emailMessageIds) {
        if (emailMessageIds.isEmpty()) {
            return Optional.empty();
        }
        return messages.findTicketIdsByEmailMessageIds(tenantId, emailMessageIds).stream().findFirst();
    }

    /** What an outbound email for this ticket message needs to know. */
    @Transactional(readOnly = true)
    public OutboundContext outboundContext(UUID tenantId, long ticketId, UUID messageId) {
        Ticket ticket = load(tenantId, ticketId);
        Customer customer = customers.findByTenantIdAndId(tenantId, ticket.getCustomerId()).orElseThrow();
        String body = messageId == null ? null : messages.findByTenantIdAndId(tenantId, messageId)
                .map(Message::getBody).orElseThrow(() -> new NotFoundException("Message", messageId));
        String lastInbound = messages.findFirstByTenantIdAndTicketIdAndDirectionAndEmailMessageIdNotNullOrderByCreatedAtDesc(
                tenantId, ticketId, MessageDirection.INBOUND).map(Message::getEmailMessageId).orElse(null);
        return new OutboundContext(ticketId, ticket.getSubject(), customer.getEmail(), customer.getName(), body,
                lastInbound, messages.findEmailMessageIds(tenantId, ticketId));
    }

    public record OutboundContext(long ticketId, String subject, String customerEmail, String customerName,
                                  String body, String inReplyTo, List<String> references) {}

    /** Storage location of an attachment, verified to belong to the ticket within the tenant. */
    @Transactional(readOnly = true)
    public NewAttachment attachment(UUID tenantId, long ticketId, UUID attachmentId) {
        Attachment a = attachments.findByTenantIdAndId(tenantId, attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment", attachmentId));
        Message m = messages.findByTenantIdAndId(tenantId, a.getMessageId()).orElseThrow();
        if (m.getTicketId() != ticketId) {
            throw new NotFoundException("Attachment", attachmentId);
        }
        return new NewAttachment(a.getId(), a.getFilename(), a.getContentType(), a.getSizeBytes(), a.getStorageKey());
    }

    // ---- internals ------------------------------------------------------------------------

    private void transition(Ticket ticket, TicketStatus target, UUID actorId, Instant now) {
        TicketStatus from = ticket.getStatus();
        if (!from.canTransitionTo(target)) {
            throw new ConflictException("Cannot change ticket #" + ticket.getId() + " from " + from + " to " + target);
        }
        ticket.changeStatus(target, now);
        UUID tenantId = ticket.getTenantId();
        long id = ticket.getId();
        SlaSnapshot snapshot = null;
        if (target == TicketStatus.PENDING_CUSTOMER) {
            snapshot = sla.pause(tenantId, id, now);
        } else if (target.isDone() && !from.isDone()) {
            snapshot = sla.resolved(tenantId, id, now);
        } else if (from == TicketStatus.PENDING_CUSTOMER && target == TicketStatus.OPEN) {
            snapshot = sla.resume(tenantId, id, now);
        } else if (from == TicketStatus.RESOLVED && target == TicketStatus.OPEN) {
            snapshot = sla.reopened(tenantId, id, now);
        }
        if (snapshot != null) {
            ticket.applySla(snapshot);
        }
        record(ticket, actorId, AuditType.STATUS_CHANGED, now, "from", from, "to", target);
        events.publishEvent(new TicketEvents.StatusChanged(tenantId, id, from, target, actorId, now));
    }

    private Ticket load(UUID tenantId, long ticketId) {
        return tickets.findByTenantIdAndId(tenantId, ticketId).orElseThrow(() -> new NotFoundException("Ticket", ticketId));
    }

    private static void requireVersion(Ticket ticket, long expected) {
        if (ticket.getVersion() != expected) {
            throw new StaleVersionException("Ticket", ticket.getId(), expected, ticket.getVersion());
        }
    }

    private Customer findOrCreateCustomer(UUID tenantId, String email, String name, Instant now) {
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        Customer customer = customers.findByTenantIdAndEmail(tenantId, normalized)
                .orElseGet(() -> customers.save(new Customer(tenantId, normalized, blankToNull(name), now)));
        customer.rememberName(name);
        return customer;
    }

    private void saveAttachments(UUID tenantId, UUID messageId, List<NewAttachment> files, Instant now) {
        for (NewAttachment f : files) {
            attachments.save(new Attachment(f.id(), tenantId, messageId, f.filename(), f.contentType(), f.sizeBytes(),
                    f.storageKey(), now));
        }
    }

    /** Package-private so the SLA listener can write audit entries. */
    void record(Ticket ticket, UUID actorId, AuditType type, Instant now, Object... keyValues) {
        record(ticket.getTenantId(), ticket.getId(), actorId, type, now, keyValues);
    }

    void record(UUID tenantId, long ticketId, UUID actorId, AuditType type, Instant now, Object... keyValues) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                details.put((String) keyValues[i], keyValues[i + 1].toString());
            }
        }
        audit.save(new AuditEntry(tenantId, ticketId, actorId, type, details, now));
    }

    private TicketView toView(Ticket ticket) {
        return toViews(ticket.getTenantId(), List.of(ticket)).getFirst();
    }

    private List<TicketView> toViews(UUID tenantId, List<Ticket> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, Customer> customerById = customers.findAllByTenantIdAndIdIn(tenantId,
                        list.stream().map(Ticket::getCustomerId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Customer::getId, Function.identity()));
        Set<UUID> assigneeIds = list.stream().map(Ticket::getAssigneeId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, UserView> assignees = users.getAll(tenantId, assigneeIds);
        return list.stream().map(t -> {
            Customer c = customerById.get(t.getCustomerId());
            UserView a = t.getAssigneeId() == null ? null : assignees.get(t.getAssigneeId());
            return new TicketView(t.getId(), t.getSubject(), t.getStatus(), t.getPriority(), t.getChannel(),
                    new TicketView.CustomerView(c.getId(), c.getEmail(), c.getName()),
                    t.getAssigneeId(), a == null ? null : a.displayName(), t.getTeamId(),
                    t.getFirstResponseDueAt(), t.getResolutionDueAt(), t.getNextSlaDueAt(), t.getNextSlaWarningAt(),
                    t.getFirstRespondedAt(), t.getResolvedAt(), t.isSlaBreached(), t.getCreatedAt(), t.getUpdatedAt(),
                    t.getVersion() == null ? 0 : t.getVersion());
        }).toList();
    }

    private List<MessageView> toMessageViews(UUID tenantId, List<Message> list) {
        Set<UUID> agentIds = list.stream().map(Message::getAuthorUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> customerIds = list.stream().map(Message::getAuthorCustomerId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, UserView> agents = users.getAll(tenantId, agentIds);
        Map<UUID, Customer> customerById = customerIds.isEmpty() ? Map.of()
                : customers.findAllByTenantIdAndIdIn(tenantId, customerIds).stream()
                        .collect(Collectors.toMap(Customer::getId, Function.identity()));
        Map<UUID, List<AttachmentView>> files = new HashMap<>();
        if (!list.isEmpty()) {
            attachments.findAllByTenantIdAndMessageIdIn(tenantId, list.stream().map(Message::getId).toList())
                    .forEach(a -> files.computeIfAbsent(a.getMessageId(), k -> new java.util.ArrayList<>()).add(a.toView()));
        }
        return list.stream().map(m -> {
            String author;
            if (m.getAuthorUserId() != null) {
                UserView u = agents.get(m.getAuthorUserId());
                author = u == null ? null : u.displayName();
            } else {
                Customer c = customerById.get(m.getAuthorCustomerId());
                author = c == null ? null : (c.getName() != null ? c.getName() : c.getEmail());
            }
            return new MessageView(m.getId(), m.getTicketId(), m.getDirection(), m.getAuthorUserId(), author, m.getBody(),
                    files.getOrDefault(m.getId(), List.of()), m.getCreatedAt());
        }).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
