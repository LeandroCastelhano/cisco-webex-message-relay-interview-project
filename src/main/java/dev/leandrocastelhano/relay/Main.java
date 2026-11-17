package dev.leandrocastelhano.relay;

import java.net.InetSocketAddress;
import java.time.Clock;

/** Starts the server locally because tokens are sent over plain HTTP. */
public final class Main {
    private Main() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Usage: java -jar message-relay.jar [port]");
        int port = args.length == 0 ? 8080 : Integer.parseInt(args[0]);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be 1-65535");

        var config = RelayConfig.fromSystemProperties();
        config.applyHttpProperties();
        var relay = new MessageRelay(config.limits(), Clock.systemUTC());
        var server = new RelayHttpServer(new InetSocketAddress("127.0.0.1", port), relay, config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        System.out.println("Message relay listening at http://127.0.0.1:" + server.port());
    }
}
