# MCDT: Minecraft DevTools

Client-side Fabric mod for Minecraft 1.21.4 that exposes the running game via a local REST and WebSocket API.

## Features (Milestone M0)

- **Loopback HTTP Server:** Javalin 7 on Jetty 12 bound to `127.0.0.1:25580` (with automatic port fallback if occupied).
- **Secure by Default:**
  - Fresh 256-bit bearer token generated per launch.
  - DNS rebinding defence (validates `Host` header against loopback / configured bind address).
  - CSRF defence (rejects unexpected `Origin` headers).
  - Query parameter token rejection to prevent accidental leakages in URLs.
  - Permission levels (`read`, `act`, `reflect-write`).
- **Bounded Task Queue:** Requests reading/writing game state cross onto the client tick thread without blocking the render loop, drained under a configurable time budget (default 2 ms/tick).
- **Interactive Documentation:**
  - Pinned offline Scalar API reference served at `http://127.0.0.1:<port>/`.
  - ReDoc reference served at `/redoc`.
  - OpenAPI 3.1 schema generated at `/openapi.json`.
- **RFC 9457 Problem JSON:** Typed, consistent error payloads (`application/problem+json`).
- **Discovery Files:** Process metadata and bearer token written to `~/.mcdt/instances/<pid>.json` and `<gameDir>/config/mcdt/instance.json` with user-only permissions.
- **Diagnostics:** `GET /v1/health` reporting mod version, Minecraft version, uptime, client tick, queue metrics, and Baritone status.

## Development & Testing

### Building
```bash
./gradlew build
```
Builds the jar with all runtime libraries (Javalin, Jetty 12, Jackson, OpenAPI, Scalar) packaged via Loom jar-in-jar (`META-INF/jars`).

### Running Tests
```bash
./gradlew test
```
Runs the JUnit 5 test suite covering `TaskQueue`, `AccessFilter`, `ConfigStore`, `DiscoveryFile`, `BearerToken`, and `HttpServer`.

### Running the Client in Development
```bash
./gradlew runClient
```

### Querying the API
Once the game starts, find your instance's port and bearer token in:
- Linux / macOS: `~/.mcdt/instances/<pid>.json`
- Windows: `%USERPROFILE%\.mcdt\instances\<pid>.json`

Call the health endpoint:
```bash
curl -H "Authorization: Bearer <TOKEN>" http://127.0.0.1:25580/v1/health
```

Or open `http://127.0.0.1:25580/` in your browser to view the interactive Scalar docs.
