package com.tickwise.sla.escalation;

import static org.assertj.core.api.Assertions.assertThat;

import com.tickwise.notification.NotificationService;
import com.tickwise.notification.NotificationView;
import com.tickwise.shared.domain.Priority;
import com.tickwise.sla.BusinessCalendarService;
import com.tickwise.sla.BusinessCalendarView;
import com.tickwise.sla.SlaMetric;
import com.tickwise.sla.SlaPolicyService;
import com.tickwise.sla.SlaService;
import com.tickwise.assignment.AssignmentService;
import com.tickwise.support.IntegrationTest;
import com.tickwise.ticket.Channel;
import com.tickwise.ticket.TicketService;
import com.tickwise.ticket.TicketStatus;
import com.tickwise.ticket.intake.InboundMessage;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The delayed WARNING / BREACH jobs end to end: scheduling on Redis, firing with a controlled
 * clock, idempotency, and rescheduling or cancellation on pause, reply and reconciliation.
 * The tenant runs 24/7 in UTC with MEDIUM = 10 min first response / 60 min resolution.
 */
class EscalationIT extends IntegrationTest {

    @Autowired EscalationProcessor processor;
    @Autowired RedisEscalationQueue queue;
    @Autowired SlaService slaService;
    @Autowired SlaPolicyService policies;
    @Autowired BusinessCalendarService calendars;
    @Autowired TicketService tickets;
    @Autowired AssignmentService assignments;
    @Autowired NotificationService notifications;
    @Autowired StringRedisTemplate redis;

    private Fixture f;

    @BeforeEach
    void setUp() {
        redis.delete(RedisEscalationQueue.KEY);
        f = newTenant("escalate");
        calendars.replaceHours(f.tenant().id(), "UTC", Arrays.stream(DayOfWeek.values())
                .map(d -> new BusinessCalendarView.HoursView(d, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)).toList());
        policies.update(f.tenant().id(), Priority.MEDIUM, 10, 60);
    }

    @Test
    void warningAtEightyPercentThenBreachAtTheDeadline() {
        Instant t0 = clock.instant();
        long id = submitTicket(f, "pat@customer.test", "Escalate me");
        assignments.claim(f.tenant().id(), id, f.agent().id(), null);

        assertThat(queue.scheduledAt(job(id, SlaMetric.FIRST_RESPONSE, EscalationKind.WARNING))).isEqualTo(t0.plus(Duration.ofMinutes(8)));
        assertThat(queue.scheduledAt(job(id, SlaMetric.FIRST_RESPONSE, EscalationKind.BREACH))).isEqualTo(t0.plus(Duration.ofMinutes(10)));
        assertThat(processor.processDue()).isZero();

        clock.advance(Duration.ofMinutes(8));
        processor.processDue();
        assertThat(auditCount(id, "SLA_WARNING")).isEqualTo(1);
        assertThat(types(notifications.list(f.tenant().id(), f.agent().id(), false, 50))).contains("SLA_WARNING");
        assertThat(tickets.get(f.tenant().id(), id).slaBreached()).isFalse();

        clock.advance(Duration.ofMinutes(2));
        processor.processDue();
        assertThat(tickets.get(f.tenant().id(), id).slaBreached()).isTrue();
        assertThat(auditCount(id, "SLA_BREACHED")).isEqualTo(1);
        assertThat(types(notifications.list(f.tenant().id(), f.manager().id(), false, 50))).contains("SLA_BREACHED");
        assertThat(jdbc.queryForObject("select first_response_breached from ticket_metrics where ticket_id = ?",
                Boolean.class, id)).isTrue();

        // Redelivery or a second worker firing the same job changes nothing.
        processor.processDue();
        slaService.handleDue(job(id, SlaMetric.FIRST_RESPONSE, EscalationKind.BREACH), clock.instant());
        assertThat(auditCount(id, "SLA_BREACHED")).isEqualTo(1);
    }

    @Test
    void replyBeforeTheDeadlineCancelsFirstResponseEscalations() {
        long id = submitTicket(f, "pat@customer.test", "Quick answer");
        clock.advance(Duration.ofMinutes(5));

        tickets.reply(f.tenant().id(), id, f.agent().id(), "On it", null);

        assertThat(queue.scheduledAt(job(id, SlaMetric.FIRST_RESPONSE, EscalationKind.WARNING))).isNull();
        assertThat(queue.scheduledAt(job(id, SlaMetric.FIRST_RESPONSE, EscalationKind.BREACH))).isNull();
        clock.advance(Duration.ofMinutes(10));
        processor.processDue();
        assertThat(auditCount(id, "SLA_BREACHED")).isZero();
    }

    @Test
    void pauseCancelsAndResumeReschedulesWithTheRemainingBusinessTime() {
        long id = submitTicket(f, "pat@customer.test", "Waiting on you");
        clock.advance(Duration.ofMinutes(4));
        tickets.reply(f.tenant().id(), id, f.agent().id(), "Need details", TicketStatus.PENDING_CUSTOMER);
        assertThat(queue.scheduledAt(job(id, SlaMetric.RESOLUTION, EscalationKind.BREACH))).isNull();

        clock.advance(Duration.ofHours(5)); // paused: none of this counts
        Instant resumedAt = clock.instant();
        intakeService.receive(new InboundMessage(f.tenant().id(), Channel.EMAIL, "pat@customer.test", null,
                "Re: [#" + id + "] Waiting on you", "Here you go", "<details@mail.test>", List.of(), null, null));

        assertThat(tickets.get(f.tenant().id(), id).status()).isEqualTo(TicketStatus.OPEN);
        assertThat(queue.scheduledAt(job(id, SlaMetric.RESOLUTION, EscalationKind.BREACH)))
                .isEqualTo(resumedAt.plus(Duration.ofMinutes(56)));
        clock.advance(Duration.ofMinutes(55));
        processor.processDue();
        assertThat(auditCount(id, "SLA_BREACHED")).isZero();
    }

    @Test
    void earlyDeliveryIsRescheduledNotActedOn() {
        long id = submitTicket(f, "pat@customer.test", "Early");
        Instant due = queue.scheduledAt(job(id, SlaMetric.FIRST_RESPONSE, EscalationKind.BREACH));

        SlaService.DueOutcome outcome = slaService.handleDue(job(id, SlaMetric.FIRST_RESPONSE, EscalationKind.BREACH), clock.instant());

        assertThat(outcome.done()).isFalse();
        assertThat(outcome.rescheduleAt()).isEqualTo(due);
        assertThat(auditCount(id, "SLA_BREACHED")).isZero();
    }

    @Test
    void reconciliationRestoresJobsLostFromRedis() {
        long id = submitTicket(f, "pat@customer.test", "Lost job");
        Instant due = queue.scheduledAt(job(id, SlaMetric.RESOLUTION, EscalationKind.BREACH));
        redis.delete(RedisEscalationQueue.KEY);

        processor.reconcile();

        assertThat(queue.scheduledAt(job(id, SlaMetric.RESOLUTION, EscalationKind.BREACH))).isEqualTo(due);
    }

    @Test
    void lateReplyAfterAMissedBreachJobStillRecordsTheBreach() {
        long id = submitTicket(f, "pat@customer.test", "Worker was down");
        redis.delete(RedisEscalationQueue.KEY); // the breach job never fires
        clock.advance(Duration.ofMinutes(15));

        tickets.reply(f.tenant().id(), id, f.agent().id(), "Sorry for the wait", null);

        assertThat(auditCount(id, "SLA_BREACHED")).isEqualTo(1);
        assertThat(tickets.get(f.tenant().id(), id).slaBreached()).isTrue();
    }

    private EscalationJob job(long ticketId, SlaMetric metric, EscalationKind kind) {
        return new EscalationJob(f.tenant().id(), ticketId, metric, kind);
    }

    private int auditCount(long ticketId, String type) {
        return jdbc.queryForObject("select count(*) from ticket_audit where ticket_id = ? and type = ?",
                Integer.class, ticketId, type);
    }

    private static List<String> types(List<NotificationView> list) {
        return list.stream().map(NotificationView::type).toList();
    }
}
