package dev.leandrocastelhano.relay;

import java.time.Duration;

/** Groups the relay and HTTP limits. The port is passed when starting the app. */
public record RelayConfig(
        MessageRelay.Limits limits,
        int maxRequestBytes,
        int workerThreads,
        int workerQueueCapacity,
        int connectionBacklog,
        int maxConnections,
        int maxHeaderBytes,
        int requestTimeoutSeconds,
        int responseTimeoutSeconds,
        int idleConnectionTimeoutSeconds) {

    public RelayConfig {
        if (limits == null) {
            throw new IllegalArgumentException("Relay limits are required");
        }
        if (maxRequestBytes < 1 || maxRequestBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Request size must be positive and leave room for oversize detection");
        }
        if (workerThreads < 1 || workerQueueCapacity < 1 || connectionBacklog < 1
                || maxConnections < 1 || maxHeaderBytes < 1 || requestTimeoutSeconds < 1
                || responseTimeoutSeconds < 1 || idleConnectionTimeoutSeconds < 1) {
            throw new IllegalArgumentException("HTTP limits and timeouts must be positive");
        }
    }

    public static RelayConfig defaults() {
        return new RelayConfig(
                new MessageRelay.Limits(128, 64, 4096, 10, Duration.ofMinutes(5)),
                8192, 8, 32, 32, 64, 8192, 10, 10, 10);
    }

    /** Reads the supported -D settings and checks them before the server starts. */
    public static RelayConfig fromSystemProperties() {
        RelayConfig defaults = defaults();
        return new RelayConfig(defaults.limits(), defaults.maxRequestBytes(),
                defaults.workerThreads(), defaults.workerQueueCapacity(), defaults.connectionBacklog(),
                property("jdk.httpserver.maxConnections", defaults.maxConnections()),
                property("sun.net.httpserver.maxReqHeaderSize", defaults.maxHeaderBytes()),
                property("sun.net.httpserver.maxReqTime", defaults.requestTimeoutSeconds()),
                property("sun.net.httpserver.maxRspTime", defaults.responseTimeoutSeconds()),
                property("sun.net.httpserver.idleInterval", defaults.idleConnectionTimeoutSeconds()));
    }

    /** Sets the HTTP properties before the JDK server reads them. These affect the whole JVM. */
    public void applyHttpProperties() {
        System.setProperty("jdk.httpserver.maxConnections", Integer.toString(maxConnections));
        System.setProperty("sun.net.httpserver.maxReqHeaderSize", Integer.toString(maxHeaderBytes));
        System.setProperty("sun.net.httpserver.maxReqTime", Integer.toString(requestTimeoutSeconds));
        System.setProperty("sun.net.httpserver.maxRspTime", Integer.toString(responseTimeoutSeconds));
        System.setProperty("sun.net.httpserver.idleInterval", Integer.toString(idleConnectionTimeoutSeconds));
    }

    private static int property(String name, int defaultValue) {
        String value = System.getProperty(name);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a positive integer", e);
        }
    }
}
