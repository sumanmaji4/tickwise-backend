package com.tickwise.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tickwise.shared.domain.Priority;
import com.tickwise.sla.BusinessCalendarService;
import com.tickwise.sla.BusinessCalendarView;
import com.tickwise.sla.SlaPolicyService;
import com.tickwise.sla.escalation.EscalationProcessor;
import com.tickwise.assignment.AssignmentService;
import com.tickwise.support.IntegrationTest;
import com.tickwise.ticket.TicketService;
import com.tickwise.ticket.TicketStatus;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Builds a known history with a controlled clock on a 24/7 calendar and checks the numbers.
 * MEDIUM policy: 30 min first response, 240 min resolution.
 */
class ReportingIT extends IntegrationTest {

    @Autowired TicketService tickets;
    @Autowired AssignmentService assignments;
    @Autowired SlaPolicyService policies;
    @Autowired BusinessCalendarService calendars;
    @Autowired EscalationProcessor escalations;

    @Test
    void summaryTeamAndAgentReportsReflectResponseTimesAndBreaches() throws Exception {
        Fixture f = newTenant("report");
        calendars.replaceHours(f.tenant().id(), "UTC", Arrays.stream(DayOfWeek.values())
                .map(d -> new BusinessCalendarView.HoursView(d, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)).toList());
        policies.update(f.tenant().id(), Priority.MEDIUM, 30, 240);
        Instant start = clock.instant();

        // A: answered after 20 min, resolved after 2 h. B: never answered, first response breached.
        long a = submitTicket(f, "a@customer.test", "Answered");
        long b = submitTicket(f, "b@customer.test", "Ignored");
        assignments.claim(f.tenant().id(), a, f.agent().id(), null);
        clock.advance(Duration.ofMinutes(20));
        tickets.reply(f.tenant().id(), a, f.agent().id(), "Hello", null);
        clock.advance(Duration.ofMinutes(100));
        tickets.reply(f.tenant().id(), a, f.agent().id(), "Fixed", TicketStatus.RESOLVED);
        // B's first-response breach is due at +30 min. Drain fully: other tests may have left due jobs in the shared Redis.
        while (escalations.processDue() > 0) {
            // keep going
        }

        String manager = loginAs(f, f.manager());
        String range = "?from=" + start.minusSeconds(60) + "&to=" + clock.instant().plusSeconds(60);
        JsonNode summary = getJson("/api/reports/summary" + range, manager).get("metrics");

        assertThat(summary.get("created").asLong()).isEqualTo(2);
        assertThat(summary.get("open").asLong()).isEqualTo(1);
        assertThat(summary.get("firstResponseAvgMinutes").asDouble()).isEqualTo(20.0);
        assertThat(summary.get("resolutionAvgMinutes").asDouble()).isEqualTo(120.0);
        assertThat(summary.get("firstResponseBreachRate").asDouble()).isEqualTo(0.5);
        assertThat(summary.get("resolutionBreachRate").asDouble()).isEqualTo(0.0);

        JsonNode agents = getJson("/api/reports/agents" + range, manager);
        JsonNode agentRow = null;
        for (JsonNode row : agents) {
            if (row.get("id").asText().equals(f.agent().id().toString())) {
                agentRow = row;
            }
        }
        assertThat(agentRow).isNotNull();
        assertThat(agentRow.at("/metrics/created").asLong()).isEqualTo(1);
        assertThat(agentRow.at("/metrics/open").asLong()).isZero();

        assertThat(getJson("/api/reports/daily" + range, manager).get("points")).isNotEmpty();
    }

    @Test
    void reportsAreTenantScoped() throws Exception {
        Fixture acme = newTenant("scoped");
        submitTicket(acme, "a@customer.test", "Only acme");
        Fixture other = newTenant("other");

        JsonNode summary = getJson("/api/reports/summary", loginAs(other, other.manager())).get("metrics");

        assertThat(summary.get("created").asLong()).isZero();
    }
}
