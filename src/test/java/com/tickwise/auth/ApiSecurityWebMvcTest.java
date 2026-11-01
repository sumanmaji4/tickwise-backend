package com.tickwise.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tickwise.assignment.AssignmentService;
import com.tickwise.notification.NotificationService;
import com.tickwise.reporting.ReportService;
import com.tickwise.shared.security.AuthenticatedUser;
import com.tickwise.shared.security.Role;
import com.tickwise.shared.storage.ObjectStorage;
import com.tickwise.shared.web.RateLimiter;
import com.tickwise.shared.web.RequestIds;
import com.tickwise.sla.BusinessCalendarService;
import com.tickwise.sla.SlaPolicyService;
import com.tickwise.tenant.TeamService;
import com.tickwise.tenant.TenantService;
import com.tickwise.tenant.UserService;
import com.tickwise.ticket.TicketService;
import com.tickwise.ticket.intake.IntakeService;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web slice with the real security chain, JWT handling and error mapping; services are mocked.
 * Runs without Docker. The end-to-end equivalents with a database live in the *IT tests.
 */
@WebMvcTest(excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.tickwise\\.email\\..*"))
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtConfig.class, SecurityProblemHandler.class, JwtTokenService.class,
        ApiSecurityWebMvcTest.Config.class})
class ApiSecurityWebMvcTest {

    @TestConfiguration
    @EnableConfigurationProperties(SecurityProperties.class)
    static class Config {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;

    @MockitoBean AuthService authService;
    @MockitoBean UserService userService;
    @MockitoBean TenantService tenantService;
    @MockitoBean TeamService teamService;
    @MockitoBean SlaPolicyService slaPolicyService;
    @MockitoBean BusinessCalendarService businessCalendarService;
    @MockitoBean TicketService ticketService;
    @MockitoBean ObjectStorage objectStorage;
    @MockitoBean IntakeService intakeService;
    @MockitoBean RateLimiter rateLimiter;
    @MockitoBean AssignmentService assignmentService;
    @MockitoBean NotificationService notificationService;
    @MockitoBean ReportService reportService;
    @MockitoBean OnboardingService onboardingService;
    @MockitoBean com.tickwise.tenant.InvitationService invitationService;

    @Test
    void loginWithBlankFieldsReturnsValidationProblemListingEachField() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:tickwise:problem:validation-error"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[1].field").value("password"))
                .andExpect(jsonPath("$.errors[2].field").value("tenantSlug"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void malformedJsonReturnsProblemCarryingTheRequestId() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":")
                        .header(RequestIds.HEADER, "client-req-42"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", PROBLEM_JSON))
                .andExpect(header().string(RequestIds.HEADER, "client-req-42"))
                .andExpect(jsonPath("$.requestId").value("client-req-42"));
    }

    @Test
    void invalidCredentialsReturn401Problem() throws Exception {
        when(authService.login(anyString(), anyString(), anyString())).thenThrow(new InvalidCredentialsException());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantSlug\":\"acme\",\"email\":\"a@acme.test\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:tickwise:problem:invalid-credentials"));
    }

    @Test
    void loginIsRateLimitedBeforeThePasswordIsChecked() throws Exception {
        org.mockito.Mockito.doThrow(new com.tickwise.shared.error.TooManyRequestsException(java.time.Duration.ofSeconds(60)))
                .when(rateLimiter).check(org.mockito.ArgumentMatchers.startsWith("login-account:"),
                        org.mockito.ArgumentMatchers.anyInt(), any());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantSlug\":\"acme\",\"email\":\"a@acme.test\",\"password\":\"guess\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));

        org.mockito.Mockito.verifyNoInteractions(authService);
    }

    @Test
    void loginRejectsTenantSlugsThatCouldForgeLogLines() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantSlug\":\"acme\\nERROR forged\",\"email\":\"a@acme.test\",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("tenantSlug"));
    }

    @Test
    void signupIsPublicButValidatesTheSlugAndPassword() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                        {"organizationName":"Acme","slug":"Not A Slug","timezone":"UTC",
                         "adminName":"Ann","email":"ann@acme.test","password":"short"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder("password", "slug")));
        org.mockito.Mockito.verifyNoInteractions(onboardingService);
    }

    @Test
    void invalidInvitationTokenIs404Problem() throws Exception {
        when(onboardingService.preview(anyString()))
                .thenThrow(new com.tickwise.shared.error.NotFoundException("Invitation", "token"));

        mvc.perform(post("/api/auth/invitations/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"nope\"}"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", PROBLEM_JSON));
    }

    @Test
    void onlyAdminsMayInviteOrDeleteTickets() throws Exception {
        String agent = bearer(user(Role.AGENT));
        String manager = bearer(user(Role.MANAGER));
        mvc.perform(post("/api/admin/invitations").header("Authorization", manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@acme.test\",\"role\":\"AGENT\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/tickets/1")
                        .header("Authorization", agent))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/tickets/1")
                        .header("Authorization", manager))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verify(ticketService, org.mockito.Mockito.never())
                .delete(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    void agentTicketCreationRequiresCustomerSubjectDescriptionAndPriority() throws Exception {
        mvc.perform(post("/api/tickets").header("Authorization", bearer(user(Role.AGENT)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"customerEmail\":\"bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(
                        org.hamcrest.Matchers.containsInAnyOrder("customerEmail", "description", "priority", "subject")));
    }

    @Test
    void protectedEndpointWithoutTokenReturns401Problem() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().string("Content-Type", PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:tickwise:problem:unauthorized"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void tamperedTokenReturns401Problem() throws Exception {
        String token = tokens.issue(user(Role.AGENT)).value();
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", PROBLEM_JSON));
    }

    @Test
    void adminEndpointRejectsAgentWith403Problem() throws Exception {
        mvc.perform(get("/api/admin/users").header("Authorization", bearer(user(Role.AGENT))))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:tickwise:problem:forbidden"));
    }

    @Test
    void tenantComesFromTokenAndRequestParametersAreIgnored() throws Exception {
        AuthenticatedUser admin = user(Role.ADMIN);
        when(userService.list(any())).thenReturn(List.of());

        mvc.perform(get("/api/admin/users").param("tenantId", UUID.randomUUID().toString())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        verify(userService).list(admin.tenantId());
    }

    @Test
    void customersCannotUseTheAgentTicketApi() throws Exception {
        mvc.perform(get("/api/tickets").header("Authorization", bearer(user(Role.CUSTOMER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentsCannotSeeReportsButManagersCan() throws Exception {
        mvc.perform(get("/api/reports/summary").header("Authorization", bearer(user(Role.AGENT))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/summary").header("Authorization", bearer(user(Role.MANAGER))))
                .andExpect(status().isOk());
    }

    @Test
    void onlyManagersAndAdminsMayAssignTicketsToOthers() throws Exception {
        String body = "{\"assigneeId\":\"" + UUID.randomUUID() + "\"}";
        mvc.perform(put("/api/tickets/1/assignee").header("Authorization", bearer(user(Role.AGENT)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", PROBLEM_JSON));
    }

    @Test
    void publicFormNeedsNoTokenButValidatesInput() throws Exception {
        mvc.perform(post("/api/public/acme/tickets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:tickwise:problem:validation-error"));
    }

    @Test
    void rateLimitedRequestsGet429WithRetryAfter() throws Exception {
        when(tenantService.findBySlug("acme")).thenReturn(java.util.Optional.of(
                new com.tickwise.tenant.TenantView(UUID.randomUUID(), "acme", "Acme", java.time.ZoneId.of("UTC"))));
        org.mockito.Mockito.doThrow(new com.tickwise.shared.error.TooManyRequestsException(java.time.Duration.ofSeconds(42)))
                .when(rateLimiter).check(anyString(), org.mockito.ArgumentMatchers.anyInt(), any());

        mvc.perform(post("/api/public/acme/tickets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"email\":\"a@b.test\",\"subject\":\"S\",\"message\":\"M\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.type").value("urn:tickwise:problem:rate-limited"));
    }

    @Test
    void unknownRouteReturnsProblem() throws Exception {
        mvc.perform(get("/api/does-not-exist").header("Authorization", bearer(user(Role.AGENT))))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", PROBLEM_JSON))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    private String bearer(AuthenticatedUser user) {
        return "Bearer " + tokens.issue(user).value();
    }

    private static AuthenticatedUser user(Role role) {
        return new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), role);
    }
}
