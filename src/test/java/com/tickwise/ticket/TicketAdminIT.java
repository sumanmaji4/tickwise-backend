package com.tickwise.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.tickwise.support.IntegrationTest;
import com.tickwise.ticket.intake.InboundMessage;
import com.tickwise.ticket.intake.IntakeService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Agent-created tickets and admin soft delete. */
class TicketAdminIT extends IntegrationTest {

    private Fixture acme;
    private String agent;

    @BeforeEach
    void setUp() throws Exception {
        acme = newTenant("tadmin");
        agent = loginAs(acme, acme.agent());
    }

    @Test
    void agentOpensATicketOnACustomersBehalf() throws Exception {
        long id = create("caller@customer.test", "Phone: printer jammed", "HIGH");

        JsonNode detail = getJson("/api/tickets/" + id, agent);
        assertThat(detail.at("/ticket/channel").asText()).isEqualTo("AGENT");
        assertThat(detail.at("/ticket/priority").asText()).isEqualTo("HIGH");
        assertThat(detail.at("/ticket/teamId").asText()).isEqualTo(acme.team().id().toString());
        assertThat(detail.at("/ticket/customer/email").asText()).isEqualTo("caller@customer.test");
        assertThat(detail.at("/sla/firstResponse/state").asText()).isEqualTo("RUNNING");
        assertThat(getJson("/api/tickets/" + id + "/messages", agent).at("/0/body").asText()).isEqualTo("It jams on page 2.");
        JsonNode created = getJson("/api/tickets/" + id + "/audit", agent).get(0);
        assertThat(created.get("type").asText()).isEqualTo("CREATED");
        assertThat(created.get("actorUserId").asText()).isEqualTo(acme.agent().id().toString());
    }

    @Test
    void anotherTenantsTeamCannotBeUsed() throws Exception {
        Fixture other = newTenant("tadmin-other");
        mvc.perform(post("/api/tickets").header("Authorization", agent).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("customerEmail", "x@customer.test", "subject", "S",
                                "description", "D", "priority", "LOW", "teamId", other.team().id().toString()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminSoftDeleteHidesTheTicketEverywhereButKeepsTheAuditTrail() throws Exception {
        long id = submitTicket(acme, "pat@customer.test", "Spam ticket");
        String admin = loginAs(acme, acme.admin());

        mvc.perform(delete("/api/tickets/" + id).header("Authorization", agent)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/tickets/" + id).header("Authorization", admin)).andExpect(status().isNoContent());

        mvc.perform(get("/api/tickets/" + id).header("Authorization", admin)).andExpect(status().isNotFound());
        assertThat(getJson("/api/tickets", admin).get("totalElements").asLong()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ticket_metrics where ticket_id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForList("select type from ticket_audit where ticket_id = ? order by id", String.class, id))
                .first().isEqualTo("CREATED");
        assertThat(jdbc.queryForList("select type from ticket_audit where ticket_id = ? order by id", String.class, id))
                .last().isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("select count(*) from ticket_sla where ticket_id = ? and running_since is not null",
                Integer.class, id)).as("SLA clocks stopped").isZero();
        mvc.perform(delete("/api/tickets/" + id).header("Authorization", admin)).andExpect(status().isNotFound());
    }

    @Test
    void customerMailForADeletedTicketOpensANewOne() throws Exception {
        long id = submitTicket(acme, "pat@customer.test", "Old");
        mvc.perform(delete("/api/tickets/" + id).header("Authorization", loginAs(acme, acme.admin())))
                .andExpect(status().isNoContent());

        IntakeService.Result result = intakeService.receive(new InboundMessage(acme.tenant().id(), Channel.EMAIL,
                "pat@customer.test", null, "Re: [#" + id + "] Old", "Hello again", "<after-delete@mail.test>", null, id, null));

        assertThat(result.outcome()).isEqualTo(IntakeService.Outcome.CREATED);
        assertThat(result.ticketId()).isNotEqualTo(id);
    }

    private long create(String customer, String subject, String priority) throws Exception {
        String body = mvc.perform(post("/api/tickets").header("Authorization", agent).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("customerEmail", customer, "customerName", "Caller",
                                "subject", subject, "description", "It jams on page 2.", "priority", priority,
                                "teamId", acme.team().id().toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channel").value("AGENT"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }
}
