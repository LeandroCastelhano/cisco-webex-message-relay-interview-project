# Approach

I started with the main flow: register two clients, send a message, receive it, and acknowledge it. Then I covered disconnecting and reconnecting without losing pending messages, invalid requests, and resource limits.

## Design choices

I used HTTP and JSON so the protocol is easy to test with ordinary HTTP clients. The endpoints and responses are listed in the [README](README.md).

I chose the JDK HTTP server rather than Spring Boot, following the brief's preference for standard or low-level networking where practical. Spring Boot would make configuration and routing convenient, but this service is small enough to handle those directly. This kept the dependencies limited to Jackson for JSON and JUnit for tests, with the relay behaviour in my own code.

The code is split into four parts:

- `MessageRelay` holds clients, mailboxes, sessions, and messaging rules.
- `RelayHttpServer` handles HTTP requests, JSON, and status codes.
- `RelayConfig` groups the limits and validates configuration, including HTTP property overrides.
- `Main` loads the configuration, starts the server, and handles shutdown.

Each client has a mailbox backed by a `LinkedHashMap`, preserving message acceptance order. The relay operations are synchronized to keep shared state consistent. Network reads and writes happen outside that lock. One lock keeps the implementation simple, but limits throughput.

## Delivery and reconnects

A successful send means the message was accepted into memory. It does not mean Caroline has received or processed it. Polling leaves messages in place; only an acknowledgement from the recipient's current session removes them.

Closing an HTTP connection does not end the logical session. An explicit disconnect invalidates the token, and an idle session expires when its token is next checked. Registering the same name issues a new token and keeps the mailbox, including messages already polled but not acknowledged.

Repeated polls can return the same IDs. Repeated sends, even with identical content, create separate messages. A repeated acknowledgement returns 404; an old token returns 401; another recipient's acknowledgement returns 403.

## Limits and testing

Mailboxes, message sizes, registered names, connections, request buffers, workers, and queued work are bounded. Request and response deadlines limit how long a stalled client can hold resources. Full capacity can still delay or reject unrelated requests. Ctrl+C stops the server and closes active connections.

Automated tests cover sending in both directions, reconnects, session replacement and expiry, acknowledgement ownership, duplicate sends, limits, and concurrent sends. HTTP tests cover malformed requests and content types. Separate-process tests check stalled-request expiry and shutdown with an active request. Run them with the README's build command.

I also created the Bruno collection for manual checks using Maya and Caroline. The automated tests are focused; they do not cover every concurrent race, slow readers, or sustained overload.

## Limitations and next steps

State is in memory and is lost on restart. Registration does not prove ownership of a name, so the server only listens locally. Disconnected names keep their registry slots.

I would next test slow readers and resource saturation, add logging, and cover acknowledgement/reconnect races. If requirements grew, I would add idempotency keys for send retries, persistence for restart recovery, and authenticated identities with TLS for external access.

## AI use

I used AI to help plan the solution and draft code and documentation. I asked for explanations and a review against the exercise requirements, then checked the suggestions against the code and automated tests. I created the Bruno collection to make the flows easier to try manually.
