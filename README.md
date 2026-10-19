# MCDT: Minecraft DevTools

Client-side Fabric mod for Minecraft 1.21.4 that exposes the running game via a local REST and WebSocket API.

## Features

### In-Game Configuration Screen
- **Where:** install [Mod Menu](https://modrinth.com/mod/modmenu) and [YetAnotherConfigLib (YACL) 3.x](https://modrinth.com/mod/yacl), then open *Mods → Minecraft DevTools → Configure*. Both are optional; without them MCDT works as before and you edit `config/mcdt/config.json` by hand.
- **Categories:** Server, Security, Performance, Integrations. Every setting in `config.json` is editable.
- **Applying changes:** pressing *Save Changes* writes `config.json`. Settings read live (permission, quit endpoints, request timeout, tick budget, MCP and Baritone toggles, origin and multiplayer allowlists, subscriber queue size) apply immediately. Startup-only settings (enable server, port, bind address, rate limit, max request size, task queue capacity, event ring buffer size, MCP notification throttle) are marked *Requires a game restart* and show a restart prompt.

### Model Context Protocol (MCP) Server (Milestone M6)
- **Native Dual HTTP & SSE MCP Server:** Standard JSON-RPC 2.0 interface hosted at `/mcp` on the same Javalin port.
  - `POST /mcp`: Receives JSON-RPC requests, returning synchronous responses in `application/json`.
  - `GET /mcp`: Server-Sent Events (SSE) stream (`text/event-stream`) advertising endpoint URL (`event: endpoint\ndata: /mcp?sessionId=...`) and streaming real-time notifications.
- **Curated Agent Tools:**
  - `get_state`: Consolidated overview of client, player, world, inventory, and Baritone status in a single call.
  - `look`: Orient yaw and pitch or face target coordinates.
  - `move`: Timed or continuous movement inputs (forward, back, strafe, jump, sneak, sprint).
  - `interact`: World and item interactions (attack, use, swing, pick block, drop, swap hands).
  - `inventory_click`: Slot manipulation in player inventory and open container screens.
  - `screen_action`: Click widgets, type text into inputs, or close open GUI screens.
  - `chat`: Send in-game chat messages/commands, or read chat history.
  - `screenshot`: Vision-enabled viewport capture returning standard MCP image content (`type: image`, `mimeType: image/png`, Base64 data).
  - `raycast`: Line-of-sight targeting raycasts.
  - `baritone_goto`, `baritone_mine`, `baritone_follow`, `baritone_cancel`: Autonomous pathing and actions returning job IDs immediately.
  - `job_status`: Poll status of background Baritone or movement jobs.
- **Escape Hatches for Full Reach:**
  - `rest_call`: Directly execute any `/v1/*` REST endpoint from the agent.
  - `inspect`: Access live JVM reflection object graph with path traversal.
- **MCP Resources & Streaming Subscriptions:**
  - Standard resource endpoints: `mcdt://player`, `mcdt://inventory`, `mcdt://screen`, `mcdt://world`, `mcdt://hud`.
  - Real-time `notifications/resources/updated` pushed over SSE when world, player, or screen changes occur, throttled per session.
- **Cross-Platform Stdio Shim Launcher:**
  - Standalone launchers (`scripts/mcdt-mcp.py` and `scripts/mcdt-mcp.ps1`) for local desktop MCP clients (Claude Desktop, Cursor, Zed).
  - Auto-discovers running instances, performs health checks, and relays standard I/O JSON-RPC to `/mcp`.
- **Diagnostics & Logging:**
  - `GET /v1/mcp/status`: Active session count, tool registry status, and configuration.
  - `GET /v1/mcp/log`: In-memory ring buffer logging recent tool calls, durations, and outcomes.

### High-Level Control & Writes (Milestone M5)
- **Reflection Field Writes:** `PUT /v1/objects/{handle}/fields/{name}` allowing dynamic mutation of instance or static fields on loaded objects, with rich type coercion for primitives, strings, enums, handles, `BlockPos`, `Vec3`, and `ResourceLocation`. Guarded by `reflect-write` permission.
- **Dynamic Method Invocation:** `POST /v1/objects/{handle}/invoke` executing arbitrary instance or static methods with typed arguments, returning converted values or handles to return objects.
- **Raw Network Packet Injection:** `POST /v1/packets` instantiating and transmitting arbitrary Minecraft serverbound packets dynamically (e.g. `ServerboundChatPacket`, `ServerboundPlayerCommandPacket`, `ServerboundSwingPacket`).
- **Baritone Pathfinding & Automation (Optional Integration):**
  - Modular, classloader-isolated integration via `BaritoneService` and `BaritoneBridge`. When Baritone is absent, returns RFC 9457 HTTP 501 `baritone-not-installed` with zero runtime classpath errors.
  - 13 dedicated automation endpoints:
    - `GET /v1/baritone/status`: Live pathing state, current process, and active goal.
    - `POST /v1/baritone/path`: Request custom path calculation to a goal.
    - `POST /v1/baritone/goto`: Walk and pathfind to a target coordinate (`BlockPos` or `xz`).
    - `POST /v1/baritone/mine`: Autonomous block mining by block ID or tag.
    - `POST /v1/baritone/follow`: Follow entities or players by ID, type, or username.
    - `POST /v1/baritone/explore`: Explore unvisited chunks around an origin.
    - `POST /v1/baritone/farm`: Automated farmland harvesting, tilling, and planting.
    - `POST /v1/baritone/build`: Build schematics at world positions.
    - `POST /v1/baritone/cancel`: Immediately stop any running pathfinding or automation process.
    - `GET /v1/baritone/jobs`: List tracked pathing and automation jobs.
    - `GET /v1/baritone/jobs/{id}`: Detailed status and outcome of an individual job.
    - `GET /v1/baritone/settings` & `PATCH /v1/baritone/settings`: Inspect and modify Baritone configuration (e.g., `allowBreak`, `allowSprint`, `primaryTimeoutMS`).
    - `POST /v1/baritone/command`: Execute native Baritone commands directly through its command pipeline.
  - Automatically streams `baritone.path_event` notifications onto the event bus.
- **Multiplayer Safety Policies:**
  - Configurable server whitelist `allowedMultiplayerServers` in `mcdt.json`.
  - When connected to an unapproved multiplayer server, destructive actions (`act` and `reflect-write` permissions) are rejected with HTTP 403 `multiplayer-write-disabled` while read-only inspection remains functional.

### Real-Time Event Streaming & WebSockets (Milestone M4)
- **Server-Sent Events (SSE):** `GET /v1/events?types=...` streaming real-time game events with standard `text/event-stream` framing.
- **Bi-Directional WebSocket API:** `GET /v1/ws` allowing concurrent full-duplex event streaming, live subscription management (`subscribe`, `unsubscribe`), and keepalive pings over a single persistent connection.
- **Rich Core Game Events:**
  - `tick.pulse`: Emitted at configurable tick intervals with client tick counter, FPS, and delta time.
  - `player.state`: Emitted when player position, velocity, health, food, or game mode changes.
  - `player.death` & `player.respawn`: Death notifications with death message, killer entity, and coordinates.
  - `chat.message`: Incoming chat and system messages with sender, text, and formatted JSON.
  - `screen.open` & `screen.close`: UI screen transitions and modal changes.
  - `chunk.load` & `chunk.unload`: World streaming and chunk lifecycle notifications.
  - `baritone.path_event`: Baritone path planning, progression, cancellation, and completion events.
- **Reliable Backpressure & Replay:** Fixed-capacity event queues per client with ring-buffer discard policies and configurable replay buffers for late-joining subscribers.

### Reflection Layer & Object Graph Inspection (Milestone M3)
- **Named Root Inspection:** `GET /v1/objects/roots` exposing canonical root handles (`minecraft`, `player`, `level`, `screen`, `options`, `gameRenderer`).
- **Object Graph Traversal:** `GET /v1/objects/{handle}` inspecting live object instances with depth-limited field evaluation, methods, and cycle detection.
- **Path Traversal Queries:** `GET /v1/objects/{handle}?path=...` supporting dot-separated fields, zero-argument method calls (getters), and list/array/map indexing (e.g. `minecraft?path=player.getInventory().items[0]`).
- **Targeted Field Expansion:** `GET /v1/objects/{handle}/fields/{name}` expanding individual object fields with configurable depth.
- **Static Class Inspection:** `GET /v1/classes/{name}` inspecting static fields and methods of any non-denylisted class.
- **Handle Lifecycle Management:** `DELETE /v1/objects/{handle}` releasing handles early; automatic 10-minute inactivity expiration, LRU eviction at 50,000 entries, and automatic flush on world disconnect.
- **NameService Runtime Translation:** On-demand fetching and disk caching of official Mojang 1.21.4 client mappings, translating between runtime intermediary and Mojang names.
- **Strict Security & Token Redaction:** Denylist blocking dangerous JVM reflection targets (`Runtime`, `ProcessBuilder`, `ClassLoader`, `java.lang.reflect.*`, `java.io.*`, `java.nio.file.*`, `java.net.*`, `Unsafe`), and automatic redaction of player session credentials (`accessToken`, `User` tokens).

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
- **Interactive WebUI & Documentation:**
  - Svelte 5 + Tailwind CSS developer dashboard served at `http://127.0.0.1:<port>/`.
  - Pinned offline Scalar API reference served at `/docs/scalar` (alias `/docs/scaler`).
  - ReDoc reference served at `/docs/redoc` (legacy `/redoc` redirects).
  - OpenAPI 3.1 schema generated at `/openapi.json`.
- **RFC 9457 Problem JSON:** Typed, consistent error payloads (`application/problem+json`).
- **Discovery Files:** Process metadata and bearer token written to `~/.mcdt/instances/<pid>.json` and `<gameDir>/config/mcdt/instance.json` with user-only permissions.
- **Diagnostics:** `GET /v1/health` reporting mod version, Minecraft version, uptime, client tick, queue metrics, and Baritone status.

## Development & Testing

### Building
```bash
./gradlew build
```
Builds the jar with all runtime libraries (Javalin, Jetty 12, Jackson, OpenAPI, Scalar, WebUI) packaged via Loom jar-in-jar (`META-INF/jars`). The Svelte dashboard is built using Bun (`bun run build`).

### Running Tests
```bash
./gradlew test
```
Runs the JUnit 5 test suite covering `TaskQueue`, `AccessFilter`, `ConfigStore`, `DiscoveryFile`, `BearerToken`, `HttpServer`, `DocsRoutes`, and endpoints.

### Running the Client in Development
```bash
./gradlew runClient
```

### Querying the API & Dashboard
Once the game starts, find your instance's port and bearer token in:
- Linux / macOS: `~/.mcdt/instances/<pid>.json`
- Windows: `%USERPROFILE%\.mcdt\instances\<pid>.json`

Call the health endpoint:
```bash
curl -H "Authorization: Bearer <TOKEN>" http://127.0.0.1:25580/v1/health
```

Or open `http://127.0.0.1:25580/` in your browser to view the live Svelte WebUI dashboard, or `http://127.0.0.1:25580/docs/scalar` for Scalar API docs.
