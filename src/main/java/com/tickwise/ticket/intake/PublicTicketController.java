package com.tickwise.ticket.intake;

import com.tickwise.shared.error.NotFoundException;
import com.tickwise.shared.web.RateLimiter;
import com.tickwise.tenant.TenantService;
import com.tickwise.tenant.TenantView;
import com.tickwise.ticket.Channel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated customer web form. The tenant comes from the URL slug, because there is no token. */
@RestController
@RequestMapping("/api/public/{tenantSlug}")
@Tag(name = "Public support form")
@SecurityRequirements
class PublicTicketController {

    private final TenantService tenants;
    private final IntakeService intake;
    private final RateLimiter rateLimiter;
    private final int perClientLimit;
    private final int perTenantLimit;
    private final Duration window;

    PublicTicketController(TenantService tenants, IntakeService intake, RateLimiter rateLimiter,
                           @Value("${tickwise.intake.rate-limit.per-client:5}") int perClientLimit,
                           @Value("${tickwise.intake.rate-limit.per-tenant:120}") int perTenantLimit,
                           @Value("${tickwise.intake.rate-limit.window:PT1M}") Duration window) {
        this.tenants = tenants;
        this.intake = intake;
        this.rateLimiter = rateLimiter;
        this.perClientLimit = perClientLimit;
        this.perTenantLimit = perTenantLimit;
        this.window = window;
    }

    record TenantInfo(String slug, String name) {}

    record PublicTicketRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 300) String subject,
            @NotBlank @Size(max = 20_000) String message,
            // Honeypot: hidden in the form, so only bots fill it in.
            @Size(max = 200) String website) {}

    record PublicTicketResponse(Long ticketId, String reference) {}

    @GetMapping
    @Operation(summary = "Public tenant info for the support page")
    TenantInfo tenant(@PathVariable String tenantSlug) {
        TenantView tenant = resolve(tenantSlug);
        return new TenantInfo(tenant.slug(), tenant.name());
    }

    @PostMapping("/tickets")
    @Operation(summary = "Submit a support request (rate limited per client and per tenant)")
    ResponseEntity<PublicTicketResponse> submit(@PathVariable String tenantSlug,
                                                @Valid @RequestBody PublicTicketRequest request,
                                                HttpServletRequest http) {
        TenantView tenant = resolve(tenantSlug);
        rateLimiter.check("public-form:" + tenant.id() + ":" + http.getRemoteAddr(), perClientLimit, window);
        rateLimiter.check("public-form:" + tenant.id(), perTenantLimit, window);
        if (request.website() != null && !request.website().isBlank()) {
            // Look successful so the bot learns nothing, but create nothing.
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new PublicTicketResponse(null, null));
        }
        IntakeService.Result result = intake.receive(new InboundMessage(tenant.id(), Channel.WEB, request.email(),
                request.name(), request.subject(), request.message(), null, null, null, null));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new PublicTicketResponse(result.ticketId(), "#" + result.ticketId()));
    }

    private TenantView resolve(String slug) {
        return tenants.findBySlug(slug).orElseThrow(() -> new NotFoundException("Workspace", slug));
    }
}
