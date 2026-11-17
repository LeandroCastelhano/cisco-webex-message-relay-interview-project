package dev.leandrocastelhano.relay;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Runs Main in a separate JVM so each server starts with fresh HTTP settings. */
@Timeout(25)
class MainProcessTest {
    @TempDir Path temporaryDirectory;
    private Process process;
    private int port;
    private HttpClient client;

    @BeforeEach void start() throws Exception {
        // Find a free port, then let Main start the server on it.
        try (var reservation = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = reservation.getLocalPort();
        }
        Path output = temporaryDirectory.resolve("server.log");
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        process = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                ServerProcess.class.getName(), Integer.toString(port))
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!Files.readString(output).contains("Message relay listening")) {
            assertTrue(process.isAlive(), () -> "Server exited; see " + output);
            assertTrue(System.nanoTime() < deadline, "Server startup timed out");
            Thread.sleep(25);
        }
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach void stop() throws Exception {
        try {
            if (process != null && process.isAlive()) {
                process.getOutputStream().write(1);
                process.getOutputStream().flush();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Server shutdown timed out");
            }
        } finally {
            if (client != null) client.close();
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test void incompleteRequestDoesNotBlockHealthAndExpiresWithDefaultDeadline() throws Exception {
        try (Socket slow = incompleteRequest()) {
            var health = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            assertEquals(200, client.send(health, HttpResponse.BodyHandlers.ofString()).statusCode());

            // 100 Continue confirms the server has started handling this request.
            // The timeout is ten seconds. Allow extra time for the JDK timer and slower test runs.
            slow.setSoTimeout(15_000);
            assertEquals(-1, slow.getInputStream().read(), "Incomplete request should be closed");
            assertEquals(200, client.send(health, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test void shutdownHookClosesAnActiveRequestAndExits() throws Exception {
        try (Socket slow = incompleteRequest()) {
            process.getOutputStream().write(1);
            process.getOutputStream().flush();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Shutdown hook must finish with an active request");
            assertEquals(0, process.exitValue());
            slow.setSoTimeout(2_000);
            assertEquals(-1, slow.getInputStream().read());
        }
    }

    private Socket incompleteRequest() throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(3_000);
            socket.getOutputStream().write(("POST /clients HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Type: application/json\r\nContent-Length: 100\r\n"
                    + "Expect: 100-continue\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            assertEquals("HTTP/1.1 100 Continue", reader.readLine());
            String line;
            do {
                line = reader.readLine();
                assertNotNull(line, "Connection closed before interim headers ended");
            } while (!line.isEmpty());
            return socket;
        } catch (Throwable failure) {
            socket.close();
            throw failure;
        }
    }

    public static final class ServerProcess {
        public static void main(String[] args) throws Exception {
            Main.main(args);
            // Use System.exit to run the shutdown hook on both Windows and Unix.
            System.in.read();
            System.exit(0);
        }
    }
}
