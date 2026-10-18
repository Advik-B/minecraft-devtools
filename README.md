# MCDT: Minecraft DevTools

Client-side Fabric mod for Minecraft 1.21.4 that exposes the running game via a local REST and WebSocket API.

## Features

### Actions & Real Input Injection (Milestone M2)
- **Synthetic Keyboard Injection:** `POST /v1/input/keys` simulating GLFW key presses, releases, taps, and text typing sequences with keymapping and scancode resolution.
- **Synthetic Mouse Injection:** `POST /v1/input/mouse` driving cursor movement, clicks (left/right/middle), presses, releases, and scroll wheel events.
- **Chat & Command Dispatch:** `POST /v1/chat` sending in-game chat messages or executing client commands, and `GET /v1/chat` retrieving paginated chat history.
- **Player Kinematics & Orientation:** `POST /v1/player/look` setting absolute angles, relative angular deltas, or orienting the player towards 3D target coordinates.
- **Timed Player Movement:** `POST /v1/player/move` driving forward, backward, strafe, jump, sneak, sprint, fly, and dismount inputs over N client ticks or continuously.
- **Interactive Player Actions:** `POST /v1/player/actions` performing attacks, item usage, arm swinging, pick block, offhand hand swaps, hotbar slot selection (0-8), item dropping, and targeted block/entity interaction.
- **GUI Screen & Widget Manipulation:** `POST /v1/screen/actions` clicking buttons, editing text fields (`EditBox`), and closing open screens.
- **Container Slot Operations:** `POST /v1/screen/slots` handling all container click types (`PICKUP`, `QUICK_MOVE`, `SWAP`, `CLONE`, `THROW`, `QUICK_CRAFT`, `PICKUP_ALL`).
- **Dynamic Options Modification:** `PATCH /v1/options` updating FOV, render distance, GUI scale, audio volumes, and saving changes persistently.
- **Two-Step Confirmation Quitting:** `POST /v1/session/quit` & `POST /v1/session/quit/confirm` with single-use 30-second security tokens for safe client shutdown.

### Read-Only Client & World Inspection (Milestone M1)
- **Engine & Session Diagnostics:** `GET /v1/client` reporting live FPS, window geometry, active screen name, and connection state (`menu`, `singleplayer`, `multiplayer`).
- **Player State & Kinematics:** `GET /v1/player` returning position, rotation, velocity, health, food, XP, game mode, status flags, and active potion effects.
- **Inventory Inspection:** `GET /v1/player/inventory` returning hotbar, main storage, armor, and offhand slots with item counts, names, durability, and data components.
- **World & Environment:** `GET /v1/world` reporting dimension, game time, weather (rain/thunder), difficulty, spawn coordinates, and loaded chunk count.
- **Bounding Box Block Query:** `GET /v1/world/blocks?from=x,y,z&to=x,y,z` returning palette-compressed block states for up to 32,768 blocks per call.
- **Spatial Entity Query:** `GET /v1/world/entities?radius=r&type=id&pos=x,y,z` searching entities near the player or arbitrary coordinates with type and distance filtering.
- **Targeting & Raycasting:** `GET /v1/world/raycast` querying current crosshair hit target or performing arbitrary raycasts with block and entity intersection.
- **GUI Screen Inspection:** `GET /v1/screen` inspecting the active GUI screen, complete interactive widget hierarchy (buttons, edit boxes, sliders), and container slots.
- **Registry Enumeration:** `GET /v1/registries` and `GET /v1/registries/{registry}` providing paginated registry entries across all built-in Minecraft registries.
- **Options & Controls:** `GET /v1/options` exposing game options, audio volumes per category, and key bindings.
- **Viewport Frame Capture:** `GET /v1/screenshot?scale=0.5` capturing the framebuffer with off-thread PNG compression without stalling the render thread.

### Infrastructure & Security (Milestone M0)
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
