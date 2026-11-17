# Message Relay

A small in-memory HTTP service for addressed messages between registered clients. A successful send stores a message in the recipient's mailbox. Delivery is confirmed only when the recipient acknowledges its ID.

## Build and run

```powershell
.\mvnw.cmd clean verify
java -jar target/message-relay.jar
```

On macOS/Linux, use `./mvnw clean verify`. The build runs the tests and creates `target/message-relay.jar`, including its runtime dependencies. Maven and dependencies are downloaded on first use.

The server runs at `http://127.0.0.1:8080`. To change the port, run `java -jar target/message-relay.jar 9090`. Stop it with Ctrl+C before rebuilding.

## Try it with Bruno

I created a [Bruno API collection](brunoCollection/) for the demo. Start the server, open the collection in Bruno, and run the requests in their listed order. Tokens and message IDs are saved automatically.

The flow covers Maya sending to Caroline, repeated polling, disconnect and reconnect, offline messages, acknowledgements, and Caroline replying to Maya. It also checks expected errors, such as using an old token or acknowledging someone else's message.

Each request has a test for its expected response. See the [demo notes](brunoCollection/README.md) for the order and saved variables. Start with a fresh server if you have pending messages from earlier manual tests.

## API

| Operation | Request | Success |
| --- | --- | --- |
| Register | `POST /clients` with `{"name":"Maya"}` | `201`, returns token and name |
| Send | `POST /messages` with `{"recipient":"Caroline","body":"hello"}` | `202`, returns the message and its ID |
| Poll | `GET /messages?limit=10` | `200`, returns pending messages |
| Acknowledge | `POST /messages/{id}/ack` | `204` |
| Disconnect | `DELETE /session` | `204` |
| Health | `GET /health` | `200` |

Use `Content-Type: application/json` for JSON bodies and `Authorization: Bearer <token>` for all operations except registration and health.

Errors return a JSON code and message: invalid input `400`, invalid session `401`, wrong recipient `403`, unknown recipient/message `404`, wrong method `405`, oversized request `413`, unsupported content type `415`, and capacity reached `429`.

## Behaviour and limits

Messages stay in the mailbox until acknowledged. Polling again can return the same IDs. Re-registering a name replaces its token and keeps its mailbox. The recipient must have registered at least once.

Defaults are 128 names, 64 messages per mailbox, 4096 bytes per message, up to 10 messages per poll, and a five-minute idle session timeout. HTTP requests and responses have ten-second deadlines, with limits on connections, request size, and worker capacity.

Defaults are grouped and validated in `RelayConfig`. The default poll size follows the relay's maximum poll batch. HTTP settings can be overridden with JVM properties before `-jar`, for example `-Dsun.net.httpserver.maxReqTime=5` (seconds). Invalid or non-positive overrides are rejected at startup.

Restarting the server loses all state. See [APPROACH.md](APPROACH.md) for the design choices and limitations.
