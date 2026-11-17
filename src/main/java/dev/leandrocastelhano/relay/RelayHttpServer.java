package dev.leandrocastelhano.relay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Handles HTTP, JSON and status codes. MessageRelay handles the messaging rules. */
public final class RelayHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ThreadPoolExecutor workers;
    private final MessageRelay relay;
    private final ObjectMapper json = new ObjectMapper();
    private final int maxRequestBytes;
    private final int defaultPollBatch;

    public RelayHttpServer(InetSocketAddress address, MessageRelay relay, RelayConfig config) throws IOException {
        java.util.Objects.requireNonNull(config);
        this.relay = java.util.Objects.requireNonNull(relay);
        this.maxRequestBytes = config.maxRequestBytes();
        this.defaultPollBatch = config.limits().maxPollBatch();
        this.server = HttpServer.create(address, config.connectionBacklog());
        this.workers = new ThreadPoolExecutor(config.workerThreads(), config.workerThreads(), 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(config.workerQueueCapacity()), new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(workers);
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }

    public int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                route(exchange);
            } catch (MessageRelay.RelayException e) {
                reply(exchange, status(e.code()), Map.of("code", e.code().name(), "message", e.getMessage()));
            } catch (BadRequest e) {
                reply(exchange, e.status, Map.of("code", e.code, "message", e.getMessage()));
            } catch (RuntimeException e) {
                // Keep internal error details out of the response.
                reply(exchange, 500, Map.of("code", "INTERNAL_ERROR", "message", "Internal server error"));
            }
        }
    }

    private void route(HttpExchange x) throws IOException {
        String method = x.getRequestMethod();
        String path = x.getRequestURI().getPath();
        if (path.equals("/clients") && method.equals("POST")) {
            JsonNode body = requestJson(x);
            reply(x, 201, relay.register(requiredText(body, "name")));
        } else if (path.equals("/messages") && method.equals("POST")) {
            JsonNode body = requestJson(x);
            reply(x, 202, relay.send(token(x), requiredText(body, "recipient"), requiredText(body, "body")));
        } else if (path.equals("/messages") && method.equals("GET")) {
            int count = defaultPollBatch;
            String query = x.getRequestURI().getRawQuery();
            if (query != null) {
                if (!query.matches("limit=[0-9]{1,3}")) throw new BadRequest(400, "INVALID_INPUT", "Expected ?limit=number");
                try { count = Integer.parseInt(query.substring(6)); }
                catch (NumberFormatException e) { throw new BadRequest(400, "INVALID_INPUT", "Invalid limit"); }
            }
            reply(x, 200, relay.poll(token(x), count));
        } else if (path.matches("/messages/[A-Za-z0-9-]{1,64}/ack") && method.equals("POST")) {
            String id = path.substring("/messages/".length(), path.length() - "/ack".length());
            relay.acknowledge(token(x), id);
            reply(x, 204, null);
        } else if (path.equals("/session") && method.equals("DELETE")) {
            relay.disconnect(token(x));
            reply(x, 204, null);
        } else if (path.equals("/health") && method.equals("GET")) {
            reply(x, 200, Map.of("status", "ok"));
        } else if (path.equals("/clients") || path.equals("/messages") || path.equals("/session")
                || path.equals("/health") || path.matches("/messages/.+/ack")) {
            throw new BadRequest(405, "METHOD_NOT_ALLOWED", "Method not allowed");
        } else {
            throw new BadRequest(404, "NOT_FOUND", "No such endpoint");
        }
    }

    private String token(HttpExchange x) {
        String authorization = x.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.substring(7).isBlank()) {
            throw new BadRequest(401, "INVALID_SESSION", "Bearer token required");
        }
        return authorization.substring(7);
    }

    private JsonNode requestJson(HttpExchange x) throws IOException {
        String type = x.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(java.util.Locale.ROOT).matches("application/json(?:\\s*;.*)?")) {
            throw new BadRequest(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json");
        }
        String length = x.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try {
                if (Long.parseLong(length) > maxRequestBytes) {
                    throw new BadRequest(413, "REQUEST_TOO_LARGE", "Request body is too large");
                }
            } catch (NumberFormatException e) {
                throw new BadRequest(400, "INVALID_INPUT", "Invalid Content-Length");
            }
        }
        byte[] bytes = x.getRequestBody().readNBytes(maxRequestBytes + 1);
        if (bytes.length > maxRequestBytes) throw new BadRequest(413, "REQUEST_TOO_LARGE", "Request body is too large");
        try {
            JsonNode node = json.readTree(bytes);
            if (node == null || !node.isObject()) throw new BadRequest(400, "INVALID_INPUT", "Expected JSON object");
            return node;
        } catch (JsonProcessingException e) {
            throw new BadRequest(400, "INVALID_INPUT", "Malformed JSON");
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new BadRequest(400, "INVALID_INPUT", "Expected text field: " + field);
        }
        return value.asText();
    }

    private void reply(HttpExchange x, int status, Object value) throws IOException {
        if (status == 204) {
            x.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = json.writeValueAsBytes(value);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
    }

    private static int status(MessageRelay.ErrorCode code) {
        return switch (code) {
            case INVALID_INPUT -> 400;
            case UNKNOWN_RECIPIENT, UNKNOWN_MESSAGE -> 404;
            case CAPACITY_EXCEEDED -> 429;
            case INVALID_SESSION -> 401;
            case WRONG_RECIPIENT -> 403;
        };
    }

    @Override public void close() {
        server.stop(1);
        workers.shutdownNow();
    }

    private static final class BadRequest extends RuntimeException {
        final int status;
        final String code;
        BadRequest(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
