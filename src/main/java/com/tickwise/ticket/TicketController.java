package com.tickwise.ticket;

import com.tickwise.shared.domain.Priority;
import com.tickwise.shared.error.BadRequestException;
import com.tickwise.shared.security.AuthenticatedUser;
import com.tickwise.shared.storage.ObjectStorage;
import com.tickwise.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Agent-facing ticket API. Staff roles only (see SecurityConfig). */
@RestController
@RequestMapping("/api/tickets")
@Validated
@Tag(name = "Tickets")
class TicketController {

    private final TicketService tickets;
    private final ObjectStorage storage;

    TicketController(TicketService tickets, ObjectStorage storage) {
        this.tickets = tickets;
        this.storage = storage;
    }

    enum SortBy {
        /**
         * Most urgent SLA deadline first; tickets without a running clock last. PostgreSQL sorts
         * NULLs last for ascending order by default; an explicit nullsLast() is not supported by
         * Spring Data JPA's criteria queries (UnsupportedOperationException at runtime).
         */
        SLA(Sort.by(Sort.Order.asc("nextSlaDueAt"), Sort.Order.asc("id"))),
        CREATED(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
        UPDATED(Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));

        final Sort sort;

        SortBy(Sort sort) {
            this.sort = sort;
        }
    }

    record UpdateTicketRequest(@NotNull Long version, TicketStatus status, Priority priority) {}

    record ReplyRequest(@NotBlank @Size(max = 50_000) String body, TicketStatus status) {}

    record CreateTicketRequest(
            @NotBlank @Email @Size(max = 320) String customerEmail,
            @Size(max = 200) String customerName,
            @NotBlank @Size(max = 300) String subject,
            @NotBlank @Size(max = 50_000) String description,
            @NotNull Priority priority,
            UUID teamId) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a ticket on a customer's behalf (channel AGENT); the customer is created if new")
    TicketView create(@AuthenticationPrincipal AuthenticatedUser caller, @Valid @RequestBody CreateTicketRequest request) {
        return tickets.create(caller.tenantId(), new NewTicket(Channel.AGENT, request.customerEmail(),
                request.customerName(), request.subject(), request.description(), request.priority(), null, null,
                request.teamId(), caller.userId()));
    }

    @DeleteMapping("/{ticketId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Soft-delete a ticket (admins only); the audit trail is kept")
    void delete(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable long ticketId,
                @RequestParam(required = false) Long version) {
        tickets.delete(caller.tenantId(), ticketId, version, caller.userId());
    }

    @GetMapping
    @Operation(summary = "Search the ticket queue")
    PageResponse<TicketView> search(@AuthenticationPrincipal AuthenticatedUser caller,
                                    @RequestParam(required = false) Set<TicketStatus> status,
                                    @RequestParam(required = false) Set<Priority> priority,
                                    @RequestParam(required = false) UUID assigneeId,
                                    @RequestParam(defaultValue = "false") boolean mine,
                                    @RequestParam(defaultValue = "false") boolean unassigned,
                                    @RequestParam(required = false) UUID teamId,
                                    @RequestParam(defaultValue = "false") boolean breached,
                                    @RequestParam(required = false) @Size(max = 200) String q,
                                    @RequestParam(defaultValue = "SLA") SortBy sort,
                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                    @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        if (mine && unassigned) {
            throw new BadRequestException("'mine' and 'unassigned' cannot be combined");
        }
        TicketQuery query = new TicketQuery(status, priority, mine ? caller.userId() : assigneeId, unassigned,
                teamId, breached, q);
        return PageResponse.of(tickets.search(caller.tenantId(), query, PageRequest.of(page, size, sort.sort)));
    }

    @GetMapping("/{ticketId}")
    @Operation(summary = "Ticket with its SLA timers")
    TicketDetail get(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable long ticketId) {
        return tickets.detail(caller.tenantId(), ticketId);
    }

    @PatchMapping("/{ticketId}")
    @Operation(summary = "Change status and/or priority; 409 if `version` is stale")
    TicketView update(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable long ticketId,
                      @Valid @RequestBody UpdateTicketRequest request) {
        return tickets.update(caller.tenantId(), ticketId, request.version(), request.status(), request.priority(),
                caller.userId());
    }

    @GetMapping("/{ticketId}/messages")
    @Operation(summary = "Conversation thread, oldest first")
    List<MessageView> messages(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable long ticketId) {
        return tickets.messages(caller.tenantId(), ticketId);
    }

    @PostMapping("/{ticketId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Reply to the customer (emailed), optionally changing status in the same step")
    MessageView reply(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable long ticketId,
                      @Valid @RequestBody ReplyRequest request) {
        return tickets.reply(caller.tenantId(), ticketId, caller.userId(), request.body(), request.status());
    }

    @GetMapping("/{ticketId}/audit")
    @Operation(summary = "Append-only history of status, priority, assignment and SLA events")
    List<AuditEntryView> audit(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable long ticketId) {
        return tickets.auditTrail(caller.tenantId(), ticketId);
    }

    @GetMapping("/{ticketId}/attachments/{attachmentId}")
    @Operation(summary = "Download an attachment")
    ResponseEntity<InputStreamResource> attachment(@AuthenticationPrincipal AuthenticatedUser caller,
                                                   @PathVariable long ticketId, @PathVariable UUID attachmentId) {
        NewAttachment file = tickets.attachment(caller.tenantId(), ticketId, attachmentId);
        // Always a download, never rendered inline: an uploaded HTML file must not run on the API origin.
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename(), java.nio.charset.StandardCharsets.UTF_8)
                                .build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(storage.get(file.storageKey())));
    }
}
