package com.tickwise.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.tickwise.support.IntegrationTest;
import com.tickwise.ticket.TicketService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Webhook → queue → worker → ticket, including signatures, idempotency, threading, loops and attachments. */
class InboundEmailIT extends IntegrationTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String CUSTOMER = "pat@customer.test";

    @Autowired InboundEmailProcessor processor;
    @Autowired OutboundEmailDispatcher dispatcher;
    @Autowired TicketService tickets;

    private Fixture acme;
    private String inbox;

    @BeforeEach
    void setUp() {
        acme = newTenant("mail");
        inbox = acme.tenant().slug() + "@inbound.tickwise.test";
        processor.processBatch(1000); // drain anything left by other tests
    }

    @Test
    void signedWebhookIsQueuedThenProcessedIntoATicketWithItsAttachment() throws Exception {
        Map<String, Object> item = email("<first@mail.customer.test>", CUSTOMER, inbox, "VPN down", "Since 9am");
        item.put("Attachments", List.of(Map.of("Name", "../trace.log", "ContentType", "text/plain", "ContentLength", 5,
                "Content", Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8)))));

        deliver(item).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1));
        assertThat(processor.processBatch(10)).isEqualTo(1);

        Map<String, Object> row = lastInbound();
        assertThat(row.get("status")).isEqualTo("PROCESSED");
        long ticketId = ((Number) row.get("ticket_id")).longValue();
        String agent = loginAs(acme, acme.agent());
        JsonNode detail = getJson("/api/tickets/" + ticketId, agent);
        assertThat(detail.at("/ticket/channel").asText()).isEqualTo("EMAIL");
        assertThat(detail.at("/ticket/subject").asText()).isEqualTo("VPN down");

        JsonNode attachment = getJson("/api/tickets/" + ticketId + "/messages", agent).at("/0/attachments/0");
        assertThat(attachment.get("filename").asText()).isEqualTo("trace.log");
        mvc.perform(get("/api/tickets/" + ticketId + "/attachments/" + attachment.get("id").asText())
                        .header("Authorization", agent))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void invalidSignatureIsRejectedAndNothingIsStored() throws Exception {
        long before = count("inbound_emails");
        byte[] body = json.writeValueAsBytes(Map.of("items", List.of(email("<x@y>", CUSTOMER, inbox, "Hi", "Hi"))));

        mvc.perform(post("/webhooks/inbound-email").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Tickwise-Signature", WebhookVerifier.sign("wrong-secret", body)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/webhooks/inbound-email").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        assertThat(count("inbound_emails")).isEqualTo(before);
    }

    @Test
    void redeliveredEmailIsRecognisedByMessageId() throws Exception {
        Map<String, Object> item = email("<dup-" + UUID.randomUUID() + "@mail.test>", CUSTOMER, inbox, "Twice", "Body");
        deliver(item);
        deliver(item);

        processor.processBatch(10);

        assertThat(jdbc.queryForList("select status from inbound_emails order by id desc limit 2", String.class))
                .containsExactlyInAnyOrder("PROCESSED", "DUPLICATE");
        assertThat(jdbc.queryForObject("select count(*) from tickets where tenant_id = ?", Integer.class,
                acme.tenant().id())).isEqualTo(1);
    }

    @Test
    void replyToOurEmailThreadsIntoTheSameTicket() throws Exception {
        long ticketId = submitTicket(acme, CUSTOMER, "Order 66");
        tickets.reply(acme.tenant().id(), ticketId, acme.agent().id(), "Which order?", null);
        String ourId = jdbc.queryForObject("select email_message_id from messages where ticket_id = ? and direction = 'OUTBOUND'",
                String.class, ticketId);

        Map<String, Object> item = email("<reply@mail.customer.test>", CUSTOMER, inbox, "Re: Order 66", "Order 66, obviously");
        item.put("InReplyTo", ourId);
        deliver(item);
        processor.processBatch(10);

        assertThat(lastInbound().get("ticket_id")).isEqualTo(ticketId);
        assertThat(lastInbound().get("outcome").toString()).contains("HEADERS");
    }

    @Test
    void replyTokenFromAStrangerOpensANewTicketInsteadOfHijacking() throws Exception {
        long ticketId = submitTicket(acme, CUSTOMER, "Private");
        String token = acme.tenant().slug() + "+t" + ticketId + "@inbound.tickwise.test";

        deliver(email("<stranger@evil.test>", "mallory@evil.test", token, "[#" + ticketId + "] hi", "let me in"));
        processor.processBatch(10);

        assertThat(lastInbound().get("outcome").toString()).startsWith("CREATED");
        assertThat(lastInbound().get("ticket_id")).isNotEqualTo(ticketId);
    }

    @Test
    void autoRepliesAreIgnoredToPreventMailLoops() throws Exception {
        Map<String, Object> item = email("<ooo@mail.test>", CUSTOMER, inbox, "Out of Office: back Monday", "Away");
        item.put("Headers", Map.of("Auto-Submitted", "auto-replied"));
        long tickets = count("tickets");

        deliver(item);
        processor.processBatch(10);

        assertThat(lastInbound().get("status")).isEqualTo("IGNORED");
        assertThat(count("tickets")).isEqualTo(tickets);
    }

    @Test
    void mailForAnUnknownWorkspaceIsIgnored() throws Exception {
        deliver(email("<nowhere@mail.test>", CUSTOMER, "nobody-here@inbound.tickwise.test", "Hello", "?"));
        processor.processBatch(10);

        assertThat(lastInbound().get("status")).isEqualTo("IGNORED");
    }

    @Test
    void outboxDeliversQueuedEmails() {
        long ticketId = submitTicket(acme, CUSTOMER, "Ack me");

        dispatcher.dispatchBatch(1000);

        assertThat(jdbc.queryForObject("select status from outbound_emails where ticket_id = ?", String.class, ticketId))
                .isEqualTo("SENT");
    }

    private Map<String, Object> email(String messageId, String from, String to, String subject, String text) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("MessageId", messageId);
        item.put("From", Map.of("Name", "Pat", "Address", from));
        item.put("To", List.of(Map.of("Address", to)));
        item.put("Subject", subject);
        item.put("RawTextBody", text);
        item.put("Headers", Map.of());
        item.put("Attachments", new ArrayList<>());
        return item;
    }

    private org.springframework.test.web.servlet.ResultActions deliver(Map<String, Object> item) throws Exception {
        byte[] body = json.writeValueAsBytes(Map.of("items", List.of(item)));
        return mvc.perform(post("/webhooks/inbound-email").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Tickwise-Signature", WebhookVerifier.sign(SECRET, body)));
    }

    private Map<String, Object> lastInbound() {
        return jdbc.queryForMap("select status, ticket_id, outcome from inbound_emails order by id desc limit 1");
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
}
