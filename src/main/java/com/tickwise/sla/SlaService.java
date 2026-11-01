package com.tickwise.sla;

import com.tickwise.shared.domain.Priority;
import com.tickwise.sla.escalation.EscalationJob;
import com.tickwise.sla.escalation.EscalationKind;
import com.tickwise.sla.escalation.EscalationScheduler;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns every ticket's SLA clocks. The ticket module calls one method per lifecycle change, in its
 * own transaction, and stores the returned deadlines on the ticket. All arithmetic is delegated
 * to {@link SlaClock} and {@link BusinessTimeCalculator}; this class adds persistence,
 * escalation scheduling and events.
 */
@Service
public class SlaService {

    private final TicketSlaRepository clocks;
    private final SlaPolicyService policies;
    private final BusinessCalendarService calendars;
    private final EscalationScheduler escalations;
    private final ApplicationEventPublisher events;

    SlaService(TicketSlaRepository clocks, SlaPolicyService policies, BusinessCalendarService calendars,
               EscalationScheduler escalations, ApplicationEventPublisher events) {
        this.clocks = clocks;
        this.policies = policies;
        this.calendars = calendars;
        this.escalations = escalations;
        this.events = events;
    }

    /** Outcome of firing a delayed job: done, or not due yet and to be re-scheduled. */
    public record DueOutcome(Instant rescheduleAt) {
        static final DueOutcome DONE = new DueOutcome(null);

        public boolean done() {
            return rescheduleAt == null;
        }
    }

    @Transactional
    public SlaSnapshot start(UUID tenantId, long ticketId, Priority priority, Instant now) {
        SlaPolicyView policy = policies.get(tenantId, priority);
        for (SlaMetric metric : SlaMetric.values()) {
            if (clocks.findByTenantIdAndTicketIdAndMetric(tenantId, ticketId, metric).isEmpty()) {
                clocks.save(new TicketSla(tenantId, ticketId, metric, SlaClock.startedAt(now, policy.target(metric))));
            }
        }
        return mutate(tenantId, ticketId, now, (sla, clock) -> clock);
    }

    /** Status became PENDING_CUSTOMER: both running clocks stop counting. */
    @Transactional
    public SlaSnapshot pause(UUID tenantId, long ticketId, Instant now) {
        BusinessTimeCalculator calc = calendars.calculatorFor(tenantId);
        return mutate(tenantId, ticketId, now, calc, (sla, clock) -> clock.pause(now, calc));
    }

    /** The customer replied or the agent moved the ticket out of PENDING_CUSTOMER. */
    @Transactional
    public SlaSnapshot resume(UUID tenantId, long ticketId, Instant now) {
        return mutate(tenantId, ticketId, now, (sla, clock) -> clock.resume(now));
    }

    /** First outbound agent reply. */
    @Transactional
    public SlaSnapshot firstResponseSent(UUID tenantId, long ticketId, Instant now) {
        BusinessTimeCalculator calc = calendars.calculatorFor(tenantId);
        return mutate(tenantId, ticketId, now, calc, (sla, clock) ->
                sla.metric() == SlaMetric.FIRST_RESPONSE ? clock.markMet(now, calc) : clock);
    }

    /**
     * Resolved or closed. The resolution target is met; a first response that never happened is
     * considered met too, since the ticket was handled.
     */
    @Transactional
    public SlaSnapshot resolved(UUID tenantId, long ticketId, Instant now) {
        BusinessTimeCalculator calc = calendars.calculatorFor(tenantId);
        return mutate(tenantId, ticketId, now, calc, (sla, clock) -> clock.markMet(now, calc));
    }

    /** A resolved ticket was reopened: resolution time keeps counting from what was consumed. */
    @Transactional
    public SlaSnapshot reopened(UUID tenantId, long ticketId, Instant now) {
        return mutate(tenantId, ticketId, now, (sla, clock) ->
                sla.metric() == SlaMetric.RESOLUTION ? clock.reopen(now) : clock);
    }

    /**
     * The ticket was deleted: stop counting and cancel every escalation job. Unlike
     * {@link #resolved}, no target counts as met, so reports and breach rates are unaffected.
     */
    @Transactional
    public SlaSnapshot stopForDeletion(UUID tenantId, long ticketId, Instant now) {
        return pause(tenantId, ticketId, now);
    }

    /** New targets from the new priority's policy; time already consumed still counts. */
    @Transactional
    public SlaSnapshot priorityChanged(UUID tenantId, long ticketId, Priority priority, Instant now) {
        SlaPolicyView policy = policies.get(tenantId, priority);
        BusinessTimeCalculator calc = calendars.calculatorFor(tenantId);
        return mutate(tenantId, ticketId, now, calc, (sla, clock) ->
                clock.isMet() ? clock : clock.retarget(policy.target(sla.metric()), now, calc));
    }

    @Transactional(readOnly = true)
    public SlaSnapshot snapshot(UUID tenantId, long ticketId, Instant now) {
        BusinessTimeCalculator calc = calendars.calculatorFor(tenantId);
        Map<SlaMetric, SlaTimer> timers = new EnumMap<>(SlaMetric.class);
        for (TicketSla sla : load(tenantId, ticketId)) {
            timers.put(sla.metric(), toTimer(sla, calc, now));
        }
        return new SlaSnapshot(timers.get(SlaMetric.FIRST_RESPONSE), timers.get(SlaMetric.RESOLUTION));
    }

    /**
     * Fires a delayed escalation. Re-reads the clock and acts only if the job still applies, so
     * duplicate, early or stale deliveries are harmless.
     */
    @Transactional
    public DueOutcome handleDue(EscalationJob job, Instant now) {
        var found = clocks.findByTenantIdAndTicketIdAndMetric(job.tenantId(), job.ticketId(), job.metric());
        if (found.isEmpty()) {
            return DueOutcome.DONE;
        }
        TicketSla sla = found.get();
        SlaClock clock = sla.clock();
        if (!clock.isRunning() || sla.breachedAt() != null) {
            return DueOutcome.DONE;
        }
        BusinessTimeCalculator calc = calendars.calculatorFor(job.tenantId());
        Instant dueAt = clock.deadline(calc).orElseThrow();
        if (job.kind() == EscalationKind.WARNING) {
            if (sla.warnedAt() != null) {
                return DueOutcome.DONE;
            }
            Instant warnAt = clock.warningAt(calc).orElseThrow();
            if (warnAt.isAfter(now)) {
                return new DueOutcome(warnAt);
            }
            sla.markWarned(now);
            events.publishEvent(new SlaEvents.Warning(sla.tenantId(), sla.ticketId(), sla.metric(), dueAt));
            return DueOutcome.DONE;
        }
        if (dueAt.isAfter(now)) {
            return new DueOutcome(dueAt);
        }
        sla.markBreached(dueAt);
        events.publishEvent(new SlaEvents.Breached(sla.tenantId(), sla.ticketId(), sla.metric(), dueAt));
        escalations.sync(sla.tenantId(), sla.ticketId(), sla.metric(), null, null);
        return DueOutcome.DONE;
    }

    /**
     * Re-schedules escalations for one batch of running clocks (keyset pagination by id) and
     * returns the last id processed, or empty when there are no more.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> resyncRunning(UUID afterId, int batchSize) {
        List<TicketSla> batch = clocks.findRunningAfter(afterId, PageRequest.of(0, batchSize));
        Map<UUID, BusinessTimeCalculator> calcs = new HashMap<>();
        for (TicketSla sla : batch) {
            scheduleEscalations(sla, calcs.computeIfAbsent(sla.tenantId(), calendars::calculatorFor));
        }
        return batch.isEmpty() ? Optional.empty() : Optional.of(batch.getLast().id());
    }

    private SlaSnapshot mutate(UUID tenantId, long ticketId, Instant now,
                               BiFunction<TicketSla, SlaClock, SlaClock> change) {
        return mutate(tenantId, ticketId, now, calendars.calculatorFor(tenantId), change);
    }

    private SlaSnapshot mutate(UUID tenantId, long ticketId, Instant now, BusinessTimeCalculator calc,
                               BiFunction<TicketSla, SlaClock, SlaClock> change) {
        Map<SlaMetric, SlaTimer> timers = new EnumMap<>(SlaMetric.class);
        for (TicketSla sla : load(tenantId, ticketId)) {
            SlaClock before = sla.clock();
            SlaClock after = change.apply(sla, before);
            if (after.isMet() && !before.isMet()) {
                onStopped(sla, before, after, now, calc);
            }
            sla.apply(after);
            if (after.isRunning() && after.warningAt(calc).orElseThrow().isAfter(now)) {
                sla.clearWarning();
            }
            scheduleEscalations(sla, calc);
            timers.put(sla.metric(), toTimer(sla, calc, now));
        }
        if (timers.size() != SlaMetric.values().length) {
            throw new IllegalStateException("Ticket " + ticketId + " has no SLA clocks; was start() called?");
        }
        return new SlaSnapshot(timers.get(SlaMetric.FIRST_RESPONSE), timers.get(SlaMetric.RESOLUTION));
    }

    /** A late stop counts as a breach even if the breach job has not fired yet (e.g. worker lag). */
    private void onStopped(TicketSla sla, SlaClock before, SlaClock after, Instant now, BusinessTimeCalculator calc) {
        boolean late = after.isBreachedAt(now, calc);
        if (late && sla.breachedAt() == null) {
            Instant dueAt = before.deadline(calc).orElse(now);
            sla.markBreached(dueAt);
            events.publishEvent(new SlaEvents.Breached(sla.tenantId(), sla.ticketId(), sla.metric(), dueAt));
        }
        events.publishEvent(new SlaEvents.Stopped(sla.tenantId(), sla.ticketId(), sla.metric(),
                after.consumed(), late || sla.breachedAt() != null, now));
    }

    private void scheduleEscalations(TicketSla sla, BusinessTimeCalculator calc) {
        SlaClock clock = sla.clock();
        Instant warnAt = null;
        Instant breachAt = null;
        if (clock.isRunning() && sla.breachedAt() == null) {
            breachAt = clock.deadline(calc).orElseThrow();
            if (sla.warnedAt() == null) {
                warnAt = clock.warningAt(calc).orElseThrow();
            }
        }
        escalations.sync(sla.tenantId(), sla.ticketId(), sla.metric(), warnAt, breachAt);
    }

    private List<TicketSla> load(UUID tenantId, long ticketId) {
        return clocks.findAllByTenantIdAndTicketId(tenantId, ticketId);
    }

    private static SlaTimer toTimer(TicketSla sla, BusinessTimeCalculator calc, Instant now) {
        SlaClock clock = sla.clock();
        SlaTimer.State state = clock.isMet() ? SlaTimer.State.MET
                : clock.isRunning() ? SlaTimer.State.RUNNING : SlaTimer.State.PAUSED;
        boolean breached = sla.breachedAt() != null || (clock.isMet() && clock.isBreachedAt(clock.metAt(), calc));
        return new SlaTimer(sla.metric(), state, clock.target().toMinutes(), clock.consumedAt(now, calc).toSeconds(),
                clock.deadline(calc).orElse(null), clock.warningAt(calc).orElse(null), clock.metAt(),
                breached, sla.breachedAt());
    }
}
