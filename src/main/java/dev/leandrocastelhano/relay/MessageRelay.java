package dev.leandrocastelhano.relay;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Keeps messages in memory. Synchronized methods protect the shared state.
 * Keep network reads and writes outside the relay lock.
 */
public final class MessageRelay {
    public enum ErrorCode {
        INVALID_INPUT, UNKNOWN_RECIPIENT, CAPACITY_EXCEEDED,
        INVALID_SESSION, WRONG_RECIPIENT, UNKNOWN_MESSAGE
    }

    public static final class RelayException extends RuntimeException {
        private final ErrorCode code;

        public RelayException(ErrorCode code, String message) {
            super(message);
            this.code = code;
        }

        public ErrorCode code() {
            return code;
        }
    }

    public record Limits(int maxClients, int maxMessagesPerMailbox,
                         int maxMessageBytes, int maxPollBatch, Duration sessionIdleTimeout) {
        public Limits {
            if (maxClients < 1 || maxMessagesPerMailbox < 1 || maxMessageBytes < 1
                    || maxPollBatch < 1 || sessionIdleTimeout == null
                    || sessionIdleTimeout.isZero() || sessionIdleTimeout.isNegative()) {
                throw new IllegalArgumentException("All relay limits must be positive");
            }
        }
    }

    public record Session(String token, String name) { }
    public record Message(String id, String from, String to, String body) { }

    private static final class Client {
        private final LinkedHashMap<String, Message> mailbox = new LinkedHashMap<>();
        private String token;
        private Instant expiresAt;
    }

    private final Limits limits;
    private final Clock clock;
    private final Map<String, Client> clients = new HashMap<>();
    private final Map<String, String> sessionOwners = new HashMap<>();
    private final Map<String, String> messageOwners = new HashMap<>();

    public MessageRelay(Limits limits, Clock clock) {
        this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Registering the same name gives it a new session and keeps its messages. */
    public synchronized Session register(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,32}")) {
            throw error(ErrorCode.INVALID_INPUT, "Name must be 1-32 ASCII letters, digits, _ or -");
        }
        Client client = clients.get(name);
        if (client == null) {
            if (clients.size() >= limits.maxClients()) {
                throw error(ErrorCode.CAPACITY_EXCEEDED, "Client registry is full");
            }
            client = new Client();
            clients.put(name, client);
        }
        if (client.token != null) {
            sessionOwners.remove(client.token);
        }
        String token;
        do {
            token = UUID.randomUUID().toString();
        } while (sessionOwners.containsKey(token));
        client.token = token;
        client.expiresAt = clock.instant().plus(limits.sessionIdleTimeout());
        sessionOwners.put(token, name);
        return new Session(token, name);
    }

    /** Stores the message. The recipient still needs to poll and acknowledge it. */
    public synchronized Message send(String token, String recipient, String body) {
        String sender = requireSession(token);
        if (recipient == null || !recipient.matches("[A-Za-z0-9_-]{1,32}")) {
            throw error(ErrorCode.INVALID_INPUT, "Invalid recipient name");
        }
        if (body == null || body.isEmpty()
                || body.getBytes(StandardCharsets.UTF_8).length > limits.maxMessageBytes()) {
            throw error(ErrorCode.INVALID_INPUT, "Message body is empty or too large");
        }
        Client destination = clients.get(recipient);
        if (destination == null) {
            throw error(ErrorCode.UNKNOWN_RECIPIENT, "Recipient has never registered");
        }
        if (destination.mailbox.size() >= limits.maxMessagesPerMailbox()) {
            throw error(ErrorCode.CAPACITY_EXCEEDED, "Recipient mailbox is full");
        }
        String id;
        do {
            id = UUID.randomUUID().toString();
        } while (messageOwners.containsKey(id));
        Message message = new Message(id, sender, recipient, body);
        destination.mailbox.put(id, message);
        messageOwners.put(id, recipient);
        return message;
    }

    /** Returns pending messages. They stay in the mailbox until acknowledged. */
    public synchronized List<Message> poll(String token, int count) {
        String name = requireSession(token);
        if (count < 1 || count > limits.maxPollBatch()) {
            throw error(ErrorCode.INVALID_INPUT, "Poll count is outside the configured limit");
        }
        List<Message> result = new ArrayList<>(Math.min(count, clients.get(name).mailbox.size()));
        for (Message message : clients.get(name).mailbox.values()) {
            if (result.size() == count) {
                break;
            }
            result.add(message);
        }
        return List.copyOf(result);
    }

    /** Removes a message only when the recipient uses its current session. */
    public synchronized void acknowledge(String token, String messageId) {
        String name = requireSession(token);
        if (messageId == null || messageId.isBlank()) {
            throw error(ErrorCode.INVALID_INPUT, "Message ID is required");
        }
        String owner = messageOwners.get(messageId);
        if (owner == null) {
            throw error(ErrorCode.UNKNOWN_MESSAGE, "Message is unknown or already acknowledged");
        }
        if (!owner.equals(name)) {
            throw error(ErrorCode.WRONG_RECIPIENT, "Only the recipient can acknowledge this message");
        }
        clients.get(name).mailbox.remove(messageId);
        messageOwners.remove(messageId);
    }

    public synchronized void disconnect(String token) {
        String name = requireSession(token);
        sessionOwners.remove(token);
        Client client = clients.get(name);
        client.token = null;
        client.expiresAt = null;
    }

    private String requireSession(String token) {
        String name = sessionOwners.get(token);
        if (name == null) {
            throw error(ErrorCode.INVALID_SESSION, "Session is unknown or replaced");
        }
        Client client = clients.get(name);
        Instant now = clock.instant();
        if (!now.isBefore(client.expiresAt)) {
            sessionOwners.remove(token);
            client.token = null;
            client.expiresAt = null;
            throw error(ErrorCode.INVALID_SESSION, "Session has expired");
        }
        client.expiresAt = now.plus(limits.sessionIdleTimeout());
        return name;
    }

    private static RelayException error(ErrorCode code, String message) {
        return new RelayException(code, message);
    }
}
