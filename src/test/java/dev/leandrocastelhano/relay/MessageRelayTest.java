package dev.leandrocastelhano.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class MessageRelayTest {
    private static MessageRelay relay(int clients, int mailbox, int bytes, Clock clock) {
        return new MessageRelay(
                new MessageRelay.Limits(clients, mailbox, bytes, 10, Duration.ofMinutes(5)), clock);
    }

    @Test
    void bothClientsCanSendAndAcknowledge() {
        MessageRelay relay = relay(2, 2, 100, Clock.systemUTC());
        var Maya = relay.register("Maya");
        var Caroline = relay.register("Caroline");
        var toCaroline = relay.send(Maya.token(), "Caroline", "hello");
        var toMaya = relay.send(Caroline.token(), "Maya", "hi");
        assertEquals(toCaroline, relay.poll(Caroline.token(), 10).getFirst());
        assertEquals(toMaya, relay.poll(Maya.token(), 10).getFirst());
        relay.acknowledge(Caroline.token(), toCaroline.id());
        relay.acknowledge(Maya.token(), toMaya.id());
        assertEquals(0, relay.poll(Caroline.token(), 10).size());
        assertEquals(0, relay.poll(Maya.token(), 10).size());
    }

    @Test
    void offlineAndUnacknowledgedMessagesSurviveReconnect() {
        MessageRelay relay = relay(2, 2, 100, Clock.systemUTC());
        var Maya = relay.register("Maya");
        var oldCaroline = relay.register("Caroline");
        var delivered = relay.send(Maya.token(), "Caroline", "first");
        assertEquals(delivered, relay.poll(oldCaroline.token(), 10).getFirst());
        relay.disconnect(oldCaroline.token());
        var offline = relay.send(Maya.token(), "Caroline", "second");
        var newCaroline = relay.register("Caroline");
        assertNotEquals(oldCaroline.token(), newCaroline.token());
        assertEquals(2, relay.poll(newCaroline.token(), 10).size());
        assertEquals(delivered, relay.poll(newCaroline.token(), 10).getFirst());
        assertCode(MessageRelay.ErrorCode.INVALID_SESSION,
                () -> relay.acknowledge(oldCaroline.token(), delivered.id()));
        assertCode(MessageRelay.ErrorCode.WRONG_RECIPIENT,
                () -> relay.acknowledge(Maya.token(), delivered.id()));
        relay.acknowledge(newCaroline.token(), delivered.id());
        assertCode(MessageRelay.ErrorCode.UNKNOWN_MESSAGE,
                () -> relay.acknowledge(newCaroline.token(), delivered.id()));
        assertEquals(offline, relay.poll(newCaroline.token(), 10).getFirst());
    }

    @Test
    void rejectsUnknownRecipientAndLimitsWithoutLosingExistingMessages() {
        MessageRelay relay = relay(2, 1, 3, Clock.systemUTC());
        var Maya = relay.register("Maya");
        var Caroline = relay.register("Caroline");
        assertCode(MessageRelay.ErrorCode.UNKNOWN_RECIPIENT,
                () -> relay.send(Maya.token(), "nobody", "ok"));
        assertCode(MessageRelay.ErrorCode.INVALID_INPUT,
                () -> relay.send(Maya.token(), "Caroline", "éé")); // Two characters, but four UTF-8 bytes.
        var first = relay.send(Maya.token(), "Caroline", "one");
        assertCode(MessageRelay.ErrorCode.CAPACITY_EXCEEDED,
                () -> relay.send(Maya.token(), "Caroline", "two"));
        assertCode(MessageRelay.ErrorCode.CAPACITY_EXCEEDED,
                () -> relay.register("charlie"));
        assertEquals(first, relay.poll(Caroline.token(), 10).getFirst());
    }

    @Test
    void expiredSessionCannotAcknowledgeButReconnectCan() {
        MutableClock clock = new MutableClock();
        MessageRelay relay = relay(2, 2, 100, clock);
        var Maya = relay.register("Maya");
        var Caroline = relay.register("Caroline");
        var message = relay.send(Maya.token(), "Caroline", "pending");
        clock.advance(Duration.ofMinutes(5));
        assertCode(MessageRelay.ErrorCode.INVALID_SESSION,
                () -> relay.acknowledge(Caroline.token(), message.id()));
        var newCaroline = relay.register("Caroline");
        assertEquals(message, relay.poll(newCaroline.token(), 10).getFirst());
    }

    @Test
    void concurrentSendsKeepEveryAcceptedMessage() throws Exception {
        MessageRelay relay = relay(3, 40, 100, Clock.systemUTC());
        var Maya = relay.register("Maya");
        var Caroline = relay.register("Caroline");
        var recipient = relay.register("recipient");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { sendTwenty(relay, Maya.token(), start); return null; });
            var b = pool.submit(() -> { sendTwenty(relay, Caroline.token(), start); return null; });
            start.countDown();
            a.get(3, TimeUnit.SECONDS);
            b.get(3, TimeUnit.SECONDS);
        }
        // Poll returns up to ten messages. Acknowledge them before reading the next batch.
        int acknowledged = 0;
        while (acknowledged < 40) {
            var page = relay.poll(recipient.token(), 10);
            assertEquals(10, page.size());
            for (var message : page) {
                relay.acknowledge(recipient.token(), message.id());
                acknowledged++;
            }
        }
        assertEquals(0, relay.poll(recipient.token(), 10).size());
    }

    @Test
    void registeringAnActiveNameReplacesItsSessionAndKeepsItsMailbox() {
        MessageRelay relay = relay(2, 2, 100, Clock.systemUTC());
        var sender = relay.register("sender");
        var oldRecipient = relay.register("recipient");
        var message = relay.send(sender.token(), "recipient", "pending");

        var newRecipient = relay.register("recipient");

        assertNotEquals(oldRecipient.token(), newRecipient.token());
        assertCode(MessageRelay.ErrorCode.INVALID_SESSION,
                () -> relay.poll(oldRecipient.token(), 10));
        assertCode(MessageRelay.ErrorCode.INVALID_SESSION,
                () -> relay.acknowledge(oldRecipient.token(), message.id()));
        assertEquals(message, relay.poll(newRecipient.token(), 10).getFirst());
        relay.acknowledge(newRecipient.token(), message.id());
        assertEquals(0, relay.poll(newRecipient.token(), 10).size());
    }

    @Test
    void identicalSendsAreSeparateMessagesInAcceptanceOrder() {
        MessageRelay relay = relay(2, 2, 100, Clock.systemUTC());
        var sender = relay.register("sender");
        var recipient = relay.register("recipient");

        var first = relay.send(sender.token(), "recipient", "same body");
        var second = relay.send(sender.token(), "recipient", "same body");

        assertNotEquals(first.id(), second.id());
        assertEquals(java.util.List.of(first, second), relay.poll(recipient.token(), 10));
    }

    private static void sendTwenty(MessageRelay relay, String token, CountDownLatch start)
            throws InterruptedException {
        if (!start.await(3, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Start latch timed out");
        }
        for (int i = 0; i < 20; i++) {
            relay.send(token, "recipient", "message " + i);
        }
    }

    private static void assertCode(MessageRelay.ErrorCode code, Runnable call) {
        assertEquals(code, assertThrows(MessageRelay.RelayException.class, call::run).code());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
