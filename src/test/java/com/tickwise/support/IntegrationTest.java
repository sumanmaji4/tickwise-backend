package com.tickwise.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickwise.shared.security.Role;
import com.tickwise.tenant.NewUser;
import com.tickwise.tenant.TeamService;
import com.tickwise.tenant.TeamView;
import com.tickwise.tenant.TenantService;
import com.tickwise.tenant.TenantView;
import com.tickwise.tenant.UserService;
import com.tickwise.tenant.UserView;
import com.tickwise.ticket.Channel;
import com.tickwise.ticket.intake.InboundMessage;
import com.tickwise.ticket.intake.IntakeService;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full application against real Postgres and Redis (Testcontainers). Tests share one database,
 * so every test creates its own tenants with unique slugs instead of relying on cleanup.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
public abstract class IntegrationTest {

    public static final String PASSWORD = "correct-horse-battery";

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MutableClock clock;
    @Autowired protected TenantService tenantService;
    @Autowired protected TeamService teamService;
    @Autowired protected UserService userService;
    @Autowired protected IntakeService intakeService;

    protected record Fixture(TenantView tenant, TeamView team, UserView admin, UserView manager, UserView agent) {}

    /** A tenant with one team, an admin, a manager and an agent in that team. */
    protected Fixture newTenant(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        TenantView tenant = tenantService.create(slug, prefix + " Inc", "Europe/London");
        TeamView team = teamService.create(tenant.id(), "Support");
        UserView admin = newUser(tenant, "admin@" + slug + ".test", Role.ADMIN, null, 0);
        UserView manager = newUser(tenant, "manager@" + slug + ".test", Role.MANAGER, team.id(), 0);
        UserView agent = newUser(tenant, "agent@" + slug + ".test", Role.AGENT, team.id(), 5);
        return new Fixture(tenant, team, admin, manager, agent);
    }

    protected UserView newUser(TenantView tenant, String email, Role role, UUID teamId, int capacity) {
        return userService.create(tenant.id(), new NewUser(email, email.substring(0, email.indexOf('@')), PASSWORD,
                role, teamId, capacity));
    }

    /** Opens a ticket the way the public web form does; returns its id. */
    protected long submitTicket(Fixture f, String customerEmail, String subject) {
        return intakeService.receive(new InboundMessage(f.tenant().id(), Channel.WEB, customerEmail, "Pat Customer",
                subject, "Please help with " + subject, null, null, null, null)).ticketId();
    }

    protected String login(TenantView tenant, String email, String password) throws Exception {
        String body = json.writeValueAsString(Map.of("tenantSlug", tenant.slug(), "email", email, "password", password));
        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(response).get("accessToken").asText();
    }

    protected String loginAs(Fixture fixture, UserView user) throws Exception {
        return login(fixture.tenant(), user.email(), PASSWORD);
    }

    protected JsonNode getJson(String url, String token) throws Exception {
        String body = mvc.perform(get(url).header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }
}
