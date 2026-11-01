package com.tickwise.ticket.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.tickwise.ticket.Channel;
import com.tickwise.ticket.TicketStatus;
import com.tickwise.ticket.intake.ThreadResolver.Candidate;
import com.tickwise.ticket.intake.ThreadResolver.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ThreadResolverTest {

    private static final String CUSTOMER = "pat@customer.test";

    private final Map<Long, Candidate> tickets = new HashMap<>();
    private final Map<String, Long> messageIds = new HashMap<>();

    private final ThreadResolver resolver = new ThreadResolver(new ThreadResolver.Lookup() {
        @Override
        public Optional<Candidate> ticket(long ticketId) {
            return Optional.ofNullable(tickets.get(ticketId));
        }

        @Override
        public Optional<Long> ticketForMessageIds(List<String> ids) {
            return ids.stream().map(messageIds::get).filter(java.util.Objects::nonNull).findFirst();
        }
    });

    private void ticket(long id, TicketStatus status) {
        tickets.put(id, new Candidate(id, status, CUSTOMER));
    }

    private static InboundMessage email(String from, String subject, List<String> thread, Long token) {
        return new InboundMessage(UUID.randomUUID(), Channel.EMAIL, from, null, subject, "body", "<new@mail>",
                thread, token, null);
    }

    @Test
    void replyTokenWinsOverEverythingElse() {
        ticket(1, TicketStatus.OPEN);
        ticket(2, TicketStatus.OPEN);
        messageIds.put("<out@tickwise>", 2L);

        var match = resolver.resolve(email(CUSTOMER, "Re: [#3] hello", List.of("<out@tickwise>"), 1L));

        assertThat(match).contains(new ThreadResolver.Match(1, Method.REPLY_TOKEN));
    }

    @Test
    void headersAreUsedWhenThereIsNoToken() {
        ticket(2, TicketStatus.PENDING_CUSTOMER);
        messageIds.put("<out@tickwise>", 2L);

        var match = resolver.resolve(email(CUSTOMER, "Re: hello", List.of("<unknown@x>", "<out@tickwise>"), null));

        assertThat(match).contains(new ThreadResolver.Match(2, Method.HEADERS));
    }

    @Test
    void headersThreadEvenFromAnotherSenderBecauseMessageIdsAreUnguessable() {
        ticket(2, TicketStatus.OPEN);
        messageIds.put("<out@tickwise>", 2L);

        assertThat(resolver.resolve(email("colleague@customer.test", "Re: hello", List.of("<out@tickwise>"), null)))
                .contains(new ThreadResolver.Match(2, Method.HEADERS));
    }

    @Test
    void subjectTagIsTheLastResort() {
        ticket(42, TicketStatus.RESOLVED);

        assertThat(resolver.resolve(email(CUSTOMER, "Re: Re: [#42] printer on fire", List.of(), null)))
                .contains(new ThreadResolver.Match(42, Method.SUBJECT));
    }

    @Test
    void guessableReferencesFromAStrangerDoNotThread() {
        ticket(42, TicketStatus.OPEN);

        assertThat(resolver.resolve(email("attacker@evil.test", "[#42] ha", List.of(), 42L))).isEmpty();
    }

    @Test
    void senderComparisonIgnoresCaseAndWhitespace() {
        ticket(42, TicketStatus.OPEN);

        assertThat(resolver.resolve(email("  Pat@Customer.TEST ", "[#42]", List.of(), null))).isPresent();
    }

    @Test
    void closedTicketsAreNeverReopenedByMail() {
        ticket(7, TicketStatus.CLOSED);
        messageIds.put("<out@tickwise>", 7L);

        assertThat(resolver.resolve(email(CUSTOMER, "Re: [#7] again", List.of("<out@tickwise>"), 7L))).isEmpty();
    }

    @Test
    void unknownReferencesStartANewTicket() {
        assertThat(resolver.resolve(email(CUSTOMER, "[#999] hello", List.of("<nope@x>"), 999L))).isEmpty();
        assertThat(resolver.resolve(email(CUSTOMER, "hello", List.of(), null))).isEmpty();
    }

    @Test
    void subjectParsing() {
        assertThat(ThreadResolver.subjectReference("Re: [#123] x")).contains(123L);
        assertThat(ThreadResolver.subjectReference("[#12a]")).isEmpty();
        assertThat(ThreadResolver.subjectReference("#123")).isEmpty();
        assertThat(ThreadResolver.subjectReference(null)).isEmpty();
        assertThat(ThreadResolver.subjectReference("[#1] and [#2]")).contains(1L);
    }
}
