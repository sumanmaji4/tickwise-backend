package com.tickwise.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.tickwise.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AuthFlowIT extends IntegrationTest {

    @Test
    void loginThenMeReturnsTheCallersIdentity() throws Exception {
        Fixture acme = newTenant("acme");
        String token = loginAs(acme, acme.agent());

        mvc.perform(get("/api/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(acme.agent().id().toString()))
                .andExpect(jsonPath("$.tenantId").value(acme.tenant().id().toString()))
                .andExpect(jsonPath("$.tenantSlug").value(acme.tenant().slug()))
                .andExpect(jsonPath("$.role").value("AGENT"))
                .andExpect(jsonPath("$.teamId").value(acme.team().id().toString()));
    }

    @Test
    void emailIsCaseInsensitive() throws Exception {
        Fixture acme = newTenant("acme");

        String token = login(acme.tenant(), acme.admin().email().toUpperCase(), PASSWORD);

        assertThat(token).startsWith("Bearer ");
    }

    @Test
    void wrongPasswordUnknownEmailAndUnknownTenantAreIndistinguishable() throws Exception {
        Fixture acme = newTenant("acme");

        JsonNode wrongPassword = failedLogin(acme.tenant().slug(), acme.admin().email(), "not-the-password");
        JsonNode unknownEmail = failedLogin(acme.tenant().slug(), "nobody@acme.test", PASSWORD);
        JsonNode unknownTenant = failedLogin("no-such-tenant", acme.admin().email(), PASSWORD);

        for (JsonNode problem : new JsonNode[] {unknownEmail, unknownTenant}) {
            assertThat(problem.get("type")).isEqualTo(wrongPassword.get("type"));
            assertThat(problem.get("detail")).isEqualTo(wrongPassword.get("detail"));
        }
    }

    @Test
    void userCannotLogInThroughAnotherTenantsSlug() throws Exception {
        Fixture acme = newTenant("acme");
        Fixture globex = newTenant("globex");

        failedLogin(globex.tenant().slug(), acme.admin().email(), PASSWORD);
    }

    private JsonNode failedLogin(String slug, String email, String password) throws Exception {
        String body = json.writeValueAsString(Map.of("tenantSlug", slug, "email", email, "password", password));
        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:tickwise:problem:invalid-credentials"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }
}
