package com.tickwise.ticket.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tickwise.support.IntegrationTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "tickwise.intake.rate-limit.per-client=3")
class PublicFormIT extends IntegrationTest {

    private Fixture acme;

    @BeforeEach
    void setUp() {
        acme = newTenant("form");
    }

    @Test
    void submissionCreatesATicketAndReturnsItsReference() throws Exception {
        mvc.perform(get("/api/public/" + acme.tenant().slug()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(acme.tenant().name()));

        String body = mvc.perform(submit(form("pat@customer.test")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value(org.hamcrest.Matchers.startsWith("#")))
                .andReturn().getResponse().getContentAsString();

        long id = json.readTree(body).get("ticketId").asLong();
        assertThat(jdbc.queryForObject("select channel from tickets where id = ?", String.class, id)).isEqualTo("WEB");
    }

    @Test
    void perClientRateLimitAnswers429WithRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(submit(form("c" + i + "@customer.test"))).andExpect(status().isCreated());
        }
        mvc.perform(submit(form("c4@customer.test")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.type").value("urn:tickwise:problem:rate-limited"));
    }

    @Test
    void honeypotSubmissionsLookAcceptedButCreateNothing() throws Exception {
        Map<String, Object> bot = form("bot@spam.test");
        bot.put("website", "http://spam.test");
        long before = jdbc.queryForObject("select count(*) from tickets where tenant_id = ?", Long.class, acme.tenant().id());

        mvc.perform(submit(bot)).andExpect(status().isAccepted());

        assertThat(jdbc.queryForObject("select count(*) from tickets where tenant_id = ?", Long.class, acme.tenant().id()))
                .isEqualTo(before);
    }

    @Test
    void unknownWorkspaceIs404() throws Exception {
        mvc.perform(post("/api/public/no-such-workspace/tickets").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(form("a@b.test"))))
                .andExpect(status().isNotFound());
    }

    private Map<String, Object> form(String email) {
        Map<String, Object> form = new HashMap<>();
        form.put("name", "Pat");
        form.put("email", email);
        form.put("subject", "Cannot log in");
        form.put("message", "It says my password is wrong.");
        return form;
    }

    private org.springframework.test.web.servlet.RequestBuilder submit(Map<String, Object> form) throws Exception {
        return post("/api/public/" + acme.tenant().slug() + "/tickets").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(form));
    }
}
