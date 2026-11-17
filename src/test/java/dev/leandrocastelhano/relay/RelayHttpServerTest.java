package dev.leandrocastelhano.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class RelayHttpServerTest {
    private RelayHttpServer server;
    private HttpClient client;
    private String base;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void start() throws Exception {
        var limits = new MessageRelay.Limits(4, 2, 64, 2, Duration.ofMinutes(5));
        var config = new RelayConfig(limits, 128, 8, 32, 32, 64, 8192, 10, 10, 10);
        server = new RelayHttpServer(new InetSocketAddress("127.0.0.1", 0),
                new MessageRelay(limits, Clock.systemUTC()), config);
        server.start();
        base = "http://127.0.0.1:" + server.port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach void stop() { server.close(); }

    @Test void offlineDeliveryAcknowledgementAndReconnect() throws Exception {
        String Maya = register("Maya");
        String Caroline = register("Caroline");
        assertEquals(204, request("DELETE", "/session", Caroline, null).statusCode());

        var sent = request("POST", "/messages", Maya, "{\"recipient\":\"Caroline\",\"body\":\"hello\"}");
        assertEquals(202, sent.statusCode());
        String id = json.readTree(sent.body()).get("id").asText();
        assertEquals(401, request("GET", "/messages", Caroline, null).statusCode());

        String newCaroline = register("Caroline");
        var first = request("GET", "/messages?limit=2", newCaroline, null);
        assertEquals(200, first.statusCode());
        assertEquals(id, json.readTree(first.body()).get(0).get("id").asText());
        assertEquals(first.body(), request("GET", "/messages", newCaroline, null).body());
        assertEquals(403, request("POST", "/messages/" + id + "/ack", Maya, null).statusCode());
        assertEquals(204, request("POST", "/messages/" + id + "/ack", newCaroline, null).statusCode());
        assertEquals(404, request("POST", "/messages/" + id + "/ack", newCaroline, null).statusCode());
        assertEquals(0, json.readTree(request("GET", "/messages", newCaroline, null).body()).size());
    }

    @Test void rejectsMalformedAndOversizedRequestsWithoutLosingService() throws Exception {
        assertEquals(415, request("POST", "/clients", null, null).statusCode());
        assertEquals(400, request("POST", "/clients", null, "{").statusCode());
        assertEquals(413, request("POST", "/clients", null, "{\"name\":\"" + "x".repeat(130) + "\"}").statusCode());
        assertEquals(400, request("POST", "/clients", null, "{\"name\":3}").statusCode());
        assertEquals(401, request("GET", "/messages", null, null).statusCode());
        assertEquals(201, request("POST", "/clients", null, "{\"name\":\"alive\"}").statusCode());
    }

    @Test void acceptsJsonContentTypeWithCharsetAndWhitespace() throws Exception {
        for (String type : new String[] {"application/json; charset=utf-8", "application/json ; charset=utf-8"}) {
            var request = HttpRequest.newBuilder(URI.create(base + "/clients"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", type)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"client\"}"))
                    .build();
            assertEquals(201, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    private String register(String name) throws Exception {
        var response = request("POST", "/clients", null, "{\"name\":\"" + name + "\"}");
        assertEquals(201, response.statusCode());
        JsonNode body = json.readTree(response.body());
        return body.get("token").asText();
    }

    private HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(3));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
