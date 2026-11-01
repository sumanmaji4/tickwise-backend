package com.tickwise.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.tickwise.support.IntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** Proves that one tenant can neither read nor reference another tenant's data. */
class TenantIsolationIT extends IntegrationTest {

    private Fixture acme;
    private Fixture globex;
    private String acmeAdmin;

    @BeforeEach
    void setUp() throws Exception {
        acme = newTenant("acme");
        globex = newTenant("globex");
        acmeAdmin = loginAs(acme, acme.admin());
    }

    @Test
    void listingUsersReturnsOnlyTheCallersTenant() throws Exception {
        JsonNode users = getJson("/api/admin/users", acmeAdmin);

        assertThat(ids(users)).containsExactlyInAnyOrder(acme.admin().id().toString(), acme.agent().id().toString());
    }

    @Test
    void listingTeamsReturnsOnlyTheCallersTenant() throws Exception {
        JsonNode teams = getJson("/api/admin/teams", acmeAdmin);

        assertThat(ids(teams)).containsExactly(acme.team().id().toString());
    }

    @Test
    void anotherTenantsUserIsNotFoundRatherThanForbidden() throws Exception {
        mvc.perform(get("/api/admin/users/{id}", globex.agent().id()).header("Authorization", acmeAdmin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:tickwise:problem:not-found"));
    }

    @Test
    void anotherTenantsTeamIsNotFound() throws Exception {
        mvc.perform(get("/api/admin/teams/{id}", globex.team().id()).header("Authorization", acmeAdmin))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotPlaceAUserInAnotherTenantsTeam() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "email", "spy@acme.test", "displayName", "Spy", "password", PASSWORD,
                "role", "AGENT", "teamId", globex.team().id().toString()));

        mvc.perform(post("/api/admin/users").header("Authorization", acmeAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());

        assertThat(userService.list(acme.tenant().id())).extracting(UserView::email).doesNotContain("spy@acme.test");
    }

    @Test
    void tenantIdInRequestIsIgnored() throws Exception {
        JsonNode users = getJson("/api/admin/users?tenantId=" + globex.tenant().id(), acmeAdmin);

        assertThat(ids(users)).doesNotContain(globex.admin().id().toString(), globex.agent().id().toString());
    }

    @Test
    void sameEmailMayExistInDifferentTenants() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "email", globex.admin().email(), "displayName", "Twin", "password", PASSWORD, "role", "AGENT"));

        mvc.perform(post("/api/admin/users").header("Authorization", acmeAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(acme.tenant().id().toString()));
    }

    @Test
    void agentsCannotUseAdminEndpoints() throws Exception {
        mvc.perform(get("/api/admin/users").header("Authorization", loginAs(acme, acme.agent())))
                .andExpect(status().isForbidden());
    }

    @Test
    void databaseRejectsCrossTenantTeamReferenceEvenIfApplicationCodeIsBypassed() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into users (id, tenant_id, email, display_name, password_hash, role, team_id,
                                   max_open_tickets, active, created_at, version)
                values (?, ?, 'raw@acme.test', 'Raw', 'x', 'AGENT', ?, 5, true, ?, 0)
                """, UUID.randomUUID(), acme.tenant().id(), globex.team().id(), java.sql.Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_users_team");
    }

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(n -> ids.add(n.get("id").asText()));
        return ids;
    }
}
