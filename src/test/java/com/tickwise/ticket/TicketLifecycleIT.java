package com.tickwise.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.tickwise.ticket.intake.InboundMessage;
import com.tickwise.ticket.intake.IntakeService;
import com.tickwise.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** End-to-end ticket lifecycle through the REST API, with SLA effects, audit and outbox. */
class TicketLifecycleIT extends IntegrationTest {

    private static final String CUSTOMER = "pat@customer.test";

    private Fixture acme;
    private String agent;

    @BeforeEach
    void setUp() throws Exception {
        acme = newTenant("acme");
        agent = loginAs(acme, acme.agent());
    }

    @Test
    void newTicketHasRunningSlaClocksAnAuditEntryAndAnAcknowledgement() throws Exception {
        long id = submitTicket(acme, CUSTOMER, "Printer on fire");

        JsonNode detail = getJson("/api/tickets/" + id, agent);
        assertThat(detail.at("/ticket/status").asText()).isEqualTo("NEW");
        assertThat(detail.at("/ticket/channel").asText()).isEqualTo("WEB");
        assertThat(detail.at("/sla/firstResponse/state").asText()).isEqualTo("RUNNING");
        assertThat(detail.at("/sla/resolution/state").asText()).isEqualTo("RUNNING");
        assertThat(detail.at("/ticket/firstResponseDueAt").asText()).isEqualTo(detail.at("/sla/firstResponse/dueAt").asText());
        assertThat(Instant.parse(detail.at("/ticket/nextSlaDueAt").asText()))
                .isEqualTo(Instant.parse(detail.at("/sla/firstResponse/dueAt").asText()));

        assertThat(auditTypes(id)).containsExactly("CREATED");
        assertThat(jdbc.queryForObject("select count(*) from outbound_emails where ticket_id = ? and auto_submitted",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void firstAgentReplyMeetsFirstResponseAndPendingPausesResolution() throws Exception {
        long id = submitTicket(acme, CUSTOMER, "Login broken");

        reply(id, "Could you send a screenshot?", "PENDING_CUSTOMER");

        JsonNode detail = getJson("/api/tickets/" + id, agent);
        assertThat(detail.at("/ticket/status").asText()).isEqualTo("PENDING_CUSTOMER");
        assertThat(detail.at("/ticket/slaPaused").asBoolean()).isTrue();
        assertThat(detail.at("/sla/firstResponse/state").asText()).isEqualTo("MET");
        assertThat(detail.at("/sla/resolution/state").asText()).isEqualTo("PAUSED");
        assertThat(detail.at("/ticket/resolutionDueAt").isMissingNode()).isTrue();
        assertThat(detail.at("/ticket/firstRespondedAt").asText()).isNotBlank();
        assertThat(auditTypes(id)).containsExactly("CREATED", "REPLY_SENT", "STATUS_CHANGED", "STATUS_CHANGED");

        Map<String, Object> email = jdbc.queryForMap(
                "select subject, reply_to, email_message_id from outbound_emails where ticket_id = ? and not auto_submitted", id);
        assertThat(email.get("subject")).isEqualTo("Re: [#" + id + "] Login broken");
        assertThat(email.get("reply_to")).isEqualTo(acme.tenant().slug() + "+t" + id + "@inbound.tickwise.test");
        assertThat(jdbc.queryForObject("select email_message_id from messages where ticket_id = ? and direction = 'OUTBOUND'",
                String.class, id)).isEqualTo(email.get("email_message_id"));
    }

    @Test
    void customerReplyToOurEmailResumesThePausedClock() throws Exception {
        long id = submitTicket(acme, CUSTOMER, "Invoice wrong");
        reply(id, "Which invoice?", "PENDING_CUSTOMER");
        String ourMessageId = jdbc.queryForObject(
                "select email_message_id from messages where ticket_id = ? and direction = 'OUTBOUND'", String.class, id);
        clock.advance(Duration.ofHours(3));

        IntakeService.Result result = intakeService.receive(new InboundMessage(acme.tenant().id(), Channel.EMAIL, CUSTOMER,
                "Pat", "Re: Invoice wrong", "INV-42", "<customer-reply-1@mail.test>", List.of(ourMessageId), null, null));

        assertThat(result.outcome()).isEqualTo(IntakeService.Outcome.APPENDED);
        assertThat(result.ticketId()).isEqualTo(id);
        JsonNode detail = getJson("/api/tickets/" + id, agent);
        assertThat(detail.at("/ticket/status").asText()).isEqualTo("OPEN");
        assertThat(detail.at("/sla/resolution/state").asText()).isEqualTo("RUNNING");
        assertThat(detail.at("/ticket/resolutionDueAt").asText()).isNotBlank();
        assertThat(getJson("/api/tickets/" + id + "/messages", agent)).hasSize(3);
    }

    @Test
    void resolvedTicketReopensWhenTheCustomerWritesAgain() throws Exception {
        long id = submitTicket(acme, CUSTOMER, "Password reset");
        reply(id, "Done, try now.", "RESOLVED");
        assertThat(getJson("/api/tickets/" + id, agent).at("/sla/resolution/state").asText()).isEqualTo("MET");

        intakeService.receive(new InboundMessage(acme.tenant().id(), Channel.EMAIL, CUSTOMER, null,
                "Re: [#" + id + "] Password reset", "Still failing", "<reopen@mail.test>", null, null, null));

        JsonNode detail = getJson("/api/tickets/" + id, agent);
        assertThat(detail.at("/ticket/status").asText()).isEqualTo("OPEN");
        assertThat(detail.at("/ticket/resolvedAt").isMissingNode()).isTrue();
        assertThat(detail.at("/sla/resolution/state").asText()).isEqualTo("RUNNING");
    }

    @Test
    void raisingPriorityRecalculatesDeadlines() throws Exception {
        long id = submitTicket(acme, CUSTOMER, "Site down");
        JsonNode before = getJson("/api/tickets/" + id, agent).get("ticket");

        JsonNode after = update(id, before.get("version").asLong(), Map.of("priority", "URGENT"));

        assertThat(after.get("priority").asText()).isEqualTo("URGENT");
        assertThat(Instant.parse(after.get("resolutionDueAt").asText()))
                .isBefore(Instant.parse(before.get("resolutionDueAt").asText()));
        assertThat(auditTypes(id)).contains("PRIORITY_CHANGED");
    }

    @Test
    void staleVersionIsRejectedWith409() throws Exception {
        long id = submitTicket(acme, CUSTOMER, "Race");
        long version = getJson("/api/tickets/" + id, agent).at("/ticket/version").asLong();
        update(id, version, Map.of("priority", "HIGH"));

        mvc.perform(patch("/api/tickets/" + id).header("Authorization", agent).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("version", version, "priority", "LOW"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:tickwise:problem:concurrent-modification"));
    }

    @Test
    void closedTicketsAreFinal() throws Exception {
        long id = submitTicket(acme, CUSTOMER, "Done");
        long version = getJson("/api/tickets/" + id, agent).at("/ticket/version").asLong();
        JsonNode closed = update(id, version, Map.of("status", "CLOSED"));

        mvc.perform(patch("/api/tickets/" + id).header("Authorization", agent).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("version", closed.get("version").asLong(), "status", "OPEN"))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/tickets/" + id + "/messages").header("Authorization", agent)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hello?\"}"))
                .andExpect(status().isConflict());

        // A customer mail to a closed ticket opens a new one instead of reopening it.
        IntakeService.Result again = intakeService.receive(new InboundMessage(acme.tenant().id(), Channel.EMAIL, CUSTOMER,
                null, "Re: [#" + id + "] Done", "One more thing", "<after-close@mail.test>", null, id, null));
        assertThat(again.outcome()).isEqualTo(IntakeService.Outcome.CREATED);
        assertThat(again.ticketId()).isNotEqualTo(id);
    }

    @Test
    void auditLogIsAppendOnlyAtTheDatabaseLevel() {
        long id = submitTicket(acme, CUSTOMER, "Audit");

        assertThatThrownBy(() -> jdbc.update("update ticket_audit set type = 'X' where ticket_id = ?", id))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from ticket_audit where ticket_id = ?", id))
                .hasMessageContaining("append-only");
    }

    @Test
    void queueSortsByNearestSlaDeadlineAndFilters() throws Exception {
        long low = submitTicket(acme, CUSTOMER, "Low one");
        long urgent = submitTicket(acme, CUSTOMER, "Urgent one");
        update(urgent, getJson("/api/tickets/" + urgent, agent).at("/ticket/version").asLong(), Map.of("priority", "URGENT"));
        update(low, getJson("/api/tickets/" + low, agent).at("/ticket/version").asLong(), Map.of("priority", "LOW"));

        JsonNode page = getJson("/api/tickets?sort=SLA&size=10", agent);
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.at("/items/0/id").asLong()).isEqualTo(urgent);

        assertThat(getJson("/api/tickets?priority=LOW", agent).at("/items/0/id").asLong()).isEqualTo(low);
        assertThat(getJson("/api/tickets?q=urgent", agent).get("totalElements").asLong()).isEqualTo(1);
        assertThat(getJson("/api/tickets?unassigned=true", agent).get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void anotherTenantCannotReadOrTouchTheTicket() throws Exception {
        long id = submitTicket(acme, CUSTOMER, "Secret");
        Fixture globex = newTenant("globex");
        String outsider = loginAs(globex, globex.agent());

        mvc.perform(get("/api/tickets/" + id).header("Authorization", outsider)).andExpect(status().isNotFound());
        mvc.perform(get("/api/tickets/" + id + "/messages").header("Authorization", outsider)).andExpect(status().isNotFound());
        mvc.perform(get("/api/tickets/" + id + "/audit").header("Authorization", outsider)).andExpect(status().isNotFound());
        mvc.perform(post("/api/tickets/" + id + "/messages").header("Authorization", outsider)
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hi\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/tickets/" + id + "/claim").header("Authorization", outsider)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/tickets/" + id).header("Authorization", outsider).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"status\":\"CLOSED\"}")).andExpect(status().isNotFound());
        assertThat(getJson("/api/tickets", outsider).get("totalElements").asLong()).isZero();
    }

    private void reply(long id, String body, String status) throws Exception {
        mvc.perform(post("/api/tickets/" + id + "/messages").header("Authorization", agent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("body", body, "status", status))))
                .andExpect(status().isCreated());
    }

    private JsonNode update(long id, long version, Map<String, Object> changes) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(changes);
        body.put("version", version);
        String response = mvc.perform(patch("/api/tickets/" + id).header("Authorization", agent)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private List<String> auditTypes(long id) throws Exception {
        List<String> types = new ArrayList<>();
        getJson("/api/tickets/" + id + "/audit", agent).forEach(e -> types.add(e.get("type").asText()));
        return types;
    }
}
