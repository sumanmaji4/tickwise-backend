package com.tickwise.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.tickwise.email.OutboundEmailDispatcher;
import com.tickwise.support.IntegrationTest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Org signup → admin invites an agent → the agent accepts and signs in. */
class OnboardingIT extends IntegrationTest {

    @Autowired OutboundEmailDispatcher dispatcher;

    @Test
    void signupCreatesTheOrganisationItsAdminDefaultsAndASession() throws Exception {
        String slug = "signup-" + UUID.randomUUID().toString().substring(0, 8);

        JsonNode session = signUp(slug, "owner@" + slug + ".test");

        String admin = "Bearer " + session.get("accessToken").asText();
        assertThat(session.get("tenantSlug").asText()).isEqualTo(slug);
        assertThat(getJson("/api/me", admin).get("role").asText()).isEqualTo("ADMIN");
        assertThat(getJson("/api/admin/sla/policies", admin)).hasSize(4);
        assertThat(getJson("/api/admin/sla/calendar", admin).get("hours")).hasSize(5);
        JsonNode teams = getJson("/api/admin/teams", admin);
        assertThat(teams).singleElement().satisfies(t -> assertThat(t.get("name").asText()).isEqualTo("Support"));
        assertThat(getJson("/api/admin/assignment-settings", admin).get("defaultTeamId").asText())
                .isEqualTo(teams.get(0).get("id").asText());
        assertThat(login(tenantService.findBySlug(slug).orElseThrow(), "owner@" + slug + ".test", PASSWORD)).startsWith("Bearer ");
    }

    @Test
    void takenSlugIsAConflict() throws Exception {
        String slug = "taken-" + UUID.randomUUID().toString().substring(0, 8);
        signUp(slug, "a@" + slug + ".test");

        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(slug, "b@" + slug + ".test")))
                .andExpect(status().isConflict());
    }

    @Test
    void invitedAgentAcceptsOnceAndCanSignIn() throws Exception {
        Fixture acme = newTenant("invite");
        String admin = loginAs(acme, acme.admin());
        String email = "new.agent@" + acme.tenant().slug() + ".test";

        JsonNode invite = invite(admin, email, acme.team().id());
        String token = invite.get("inviteUrl").asText().replaceAll(".*token=", "");
        assertThat(jdbc.queryForObject("select count(*) from invitations where token_hash = ?", Integer.class, token))
                .as("raw token is never stored").isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbound_emails where to_address = ? and ticket_id is null",
                Integer.class, email)).isEqualTo(1);

        mvc.perform(post("/api/auth/invitations/preview").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", token))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.tenantName").value(acme.tenant().name()));

        String accepted = mvc.perform(post("/api/auth/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", token, "displayName", "Nova Agent", "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String agent = "Bearer " + json.readTree(accepted).get("accessToken").asText();
        JsonNode me = getJson("/api/me", agent);
        assertThat(me.get("role").asText()).isEqualTo("AGENT");
        assertThat(me.get("teamId").asText()).isEqualTo(acme.team().id().toString());

        // single use
        mvc.perform(post("/api/auth/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", token, "displayName", "Again", "password", PASSWORD))))
                .andExpect(status().isNotFound());
        assertThat(login(acme.tenant(), email, PASSWORD)).startsWith("Bearer ");
    }

    @Test
    void revokedReplacedAndExpiredInvitationsCannotBeUsed() throws Exception {
        Fixture acme = newTenant("revoke");
        String admin = loginAs(acme, acme.admin());
        String email = "maybe@" + acme.tenant().slug() + ".test";

        JsonNode first = invite(admin, email, null);
        JsonNode second = invite(admin, email, null); // re-invite replaces the first link
        assertThat(previewStatus(token(first))).isEqualTo(404);
        assertThat(previewStatus(token(second))).isEqualTo(200);

        mvc.perform(delete("/api/admin/invitations/" + second.at("/invitation/id").asText()).header("Authorization", admin))
                .andExpect(status().isNoContent());
        assertThat(previewStatus(token(second))).isEqualTo(404);

        JsonNode third = invite(admin, email, null);
        clock.advance(Duration.ofDays(8));
        assertThat(previewStatus(token(third))).isEqualTo(404);
    }

    @Test
    void existingUsersCannotBeInvitedAgain() throws Exception {
        Fixture acme = newTenant("dupe");
        mvc.perform(post("/api/admin/invitations").header("Authorization", loginAs(acme, acme.admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", acme.agent().email(), "role", "AGENT"))))
                .andExpect(status().isConflict());
    }

    @Test
    void invitationLinkIsScrubbedFromTheOutboxOnceDelivered() throws Exception {
        Fixture acme = newTenant("scrub");
        String email = "scrub@" + acme.tenant().slug() + ".test";
        String token = token(invite(loginAs(acme, acme.admin()), email, null));

        dispatcher.dispatchBatch(1000);

        String body = jdbc.queryForObject("select body from outbound_emails where to_address = ?", String.class, email);
        assertThat(body).doesNotContain(token);
        assertThat(previewStatus(token)).as("the invitation itself still works").isEqualTo(200);
    }

    private JsonNode signUp(String slug, String email) throws Exception {
        String body = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody(slug, email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private String signupBody(String slug, String email) throws Exception {
        return json.writeValueAsString(Map.of("organizationName", "Org " + slug, "slug", slug, "timezone", "Europe/Paris",
                "adminName", "Owner", "email", email, "password", PASSWORD));
    }

    private JsonNode invite(String admin, String email, UUID teamId) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("email", email, "role", "AGENT"));
        if (teamId != null) {
            body.put("teamId", teamId.toString());
        }
        String response = mvc.perform(post("/api/admin/invitations").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private static String token(JsonNode invite) {
        return invite.get("inviteUrl").asText().replaceAll(".*token=", "");
    }

    private int previewStatus(String token) throws Exception {
        return mvc.perform(post("/api/auth/invitations/preview").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", token))))
                .andReturn().getResponse().getStatus();
    }
}
