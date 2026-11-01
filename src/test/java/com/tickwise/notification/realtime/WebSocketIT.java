package com.tickwise.notification.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tickwise.support.IntegrationTest;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** Real server, real STOMP client: events reach the right tenant's dashboards and nobody else's. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketIT extends IntegrationTest {

    @LocalServerPort int port;

    private WebSocketStompClient client;
    private Fixture acme;

    @BeforeEach
    void setUp() {
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        acme = newTenant("live");
    }

    @AfterEach
    void tearDown() {
        client.stop();
    }

    @Test
    void ticketEventsArePushedToTheTenantTopic() throws Exception {
        StompSession session = connect(loginAs(acme, acme.agent()));
        BlockingQueue<Map<String, Object>> received = subscribe(session, RealtimeMessage.tenantTopic(acme.tenant().id()));

        long id = submitTicket(acme, "pat@customer.test", "Live!");

        Map<String, Object> event = received.poll(10, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.get("type")).isEqualTo("ticket.created");
        assertThat(((Number) event.get("ticketId")).longValue()).isEqualTo(id);
    }

    @Test
    void anotherTenantsTopicCannotBeSubscribed() throws Exception {
        Fixture globex = newTenant("spy");
        StompSession session = connect(loginAs(globex, globex.agent()));
        BlockingQueue<Map<String, Object>> received = subscribe(session, RealtimeMessage.tenantTopic(acme.tenant().id()));

        submitTicket(acme, "pat@customer.test", "Not for you");

        // The interceptor rejects the SUBSCRIBE (the server answers with an ERROR frame), so nothing arrives.
        assertThat(received.poll(3, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void connectingWithoutAValidTokenFails() {
        assertThatThrownBy(() -> connect("Bearer not-a-token")).isInstanceOf(Exception.class);
    }

    private StompSession connect(String bearer) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", bearer);
        CompletableFuture<StompSession> future = client.connectAsync("ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {});
        return future.get(10, TimeUnit.SECONDS);
    }

    /**
     * Subscribes, then gives the server a moment to register the subscription before the test
     * publishes. (The simple broker does not send RECEIPT frames, so there is nothing to await.)
     */
    private static BlockingQueue<Map<String, Object>> subscribe(StompSession session, String destination) throws Exception {
        BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((Map<String, Object>) payload);
            }
        });
        Thread.sleep(500);
        return queue;
    }
}
