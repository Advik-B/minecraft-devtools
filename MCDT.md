# MCDT: Minecraft DevTools

Oct 18, 2026 · @Advik

## Abstract

MCDT is a client-side Fabric mod for Minecraft 1.21.4 that runs a local HTTP server inside the game and exposes the running client as a REST and WebSocket API. Any tool in any language can read game state, drive input, give high-level orders through Baritone when it is installed (go to, mine, follow, build), and inspect the live object graph without writing Java or restarting the game.

Today, inspecting or automating the client means writing a mod, attaching a debugger, or scraping the screen. Each option is slow to iterate on and locks you into the JVM. MCDT turns the client into a service: `curl` it, script it from Python, build a dashboard in a browser, or let an AI agent operate it.

Target users:

- **Mod developers** who want to inspect state and test behaviour without a rebuild cycle.
- **Tool builders** who want an overlay, recorder, or bot in a language other than Java.
- **Researchers and agent developers** who need a programmatic, observable Minecraft environment.
- **Technical players** who want map, inventory, or statistics tooling outside the game window.

## Goals and non-goals

MCDT aims for full reach into the client with safe defaults: everything is readable, writes are opt-in, and nothing leaves localhost.

**Goals**

1. Do everything a client can do: read all state and perform every action a player or the client itself can take, through curated endpoints, real input injection, raw packets, and reflection.
2. Let external tools drive the client: keyboard, mouse, chat, commands, screens, and options. For higher-level control, expose Baritone's pathing, mining, following, exploring, farming, and building.
3. Stream game events in real time over WebSocket.
4. Never block or stall the game thread beyond a fixed per-tick budget.
5. Behave like a proper devtool: a generated OpenAPI spec, interactive docs, request logs, and clear, typed errors.
6. Secure by default: loopback only, token required, writes disabled until enabled.

**Non-goals**

- Server-side or dedicated-server support. MCDT is a client mod.
- Arbitrary code execution (no script `eval` endpoint). Method invocation through reflection covers the need with a smaller attack surface.
- Cheating aids for multiplayer. MCDT does not hide itself from servers, and its docs will state that automation may break server rules.
- Redistribution of Mojang mappings or decompiled source. The mod ships only its own compiled code, and the API never serves mapping tables or source. Readable names at runtime come from a mappings file fetched from Mojang on the user's own machine (see Serialization and object handles).
- Official client SDKs. MCDT publishes a complete OpenAPI 3.1 spec; users generate clients in their own language with their own tooling.

## Platform choices

MCDT picks libraries for developer experience and capability, not jar size: Fabric on 1.21.4 with Mojang mappings plus Parchment, Javalin for HTTP, Jackson for JSON, and optional Baritone support for high-level control.

| Area | Choice | Reason |
| --- | --- | --- |
| Loader | Fabric Loader + Fabric API | Lightweight, fast updates, mixins for hooks Fabric API lacks |
| Game version | 1.21.4 | Current target; port forward later |
| Java | 21 | Required by 1.21.x |
| Mappings | Mojang official + Parchment layer | Names match the game and wider ecosystem; Parchment adds parameter names and Javadoc |
| HTTP and WebSocket | Javalin 7 on Jetty 12 | Mature routing, WebSocket, server-sent events, validation, and access logs out of the box |
| JSON | Jackson | Annotations, polymorphic types, streaming writes for large responses |
| API spec and docs | OpenAPI 3.1 generated from annotations (javalin-openapi), Scalar (bundled, version-pinned) at `/` | Spec never drifts from code; interactive docs in the browser |
| High-level control | Baritone 1.13.1 (`baritone-api-fabric`) | Optional: features turn on when the user installs Baritone |
| Packaging | Loom jar-in-jar (`include`) | One jar to install; every library ships inside it |
| Build | Gradle with Fabric Loom | Standard Fabric toolchain |

Jar size is not a constraint, so MCDT includes the tooling a devtool should have: interactive API docs, structured request logs under `logs/mcdt/`, and a browser console at `/console` for exploring the object graph (M5 or later). Jetty and Jackson do not overlap with Minecraft's own libraries, so relocation should not be needed. If a transitive dependency clashes with a game library (Guava or Netty versions, for example), relocate that one library with the Shadow plugin.

## System architecture

MCDT has two paths through the client: a request path that crosses a bounded task queue onto the client thread, and an event path that flows back out through a lock-free ring buffer.

&#91;embedded content: MCDT architecture · request path and event path\]

Requests flow down the left lane and across the queue; results return as JSON through a future. Events start at mixin hooks on the game, pass through the ring, and stream out over WebSocket.

Components:

- **HttpServer**: Javalin on Jetty, auth filter, router, permission check.
- **Handlers**: one class per curated resource, plus the Baritone, reflection, and batch handlers. BaritoneBridge wraps every call into the Baritone API.
- **TaskQueue and TaskRunner**: bounded queue drained by a client tick hook under a time budget.
- **Serializer**: type adapters, depth limit, cycle detection, paging.
- **HandleTable**: weak references to objects handed out by the reflection layer.
- **NameService**: intermediary-to-Mojang name translation for reflection output.
- **EventBus**: mixin and Fabric API hooks write into a ring buffer; the fan-out sends to subscribers.
- **Config**: port, bind address, permission level, budgets, origin allowlist, stored under `config/mcdt/`.

## Threading and execution model

HTTP threads never touch game objects; every request that reads or writes state becomes a task that runs on the client thread and returns through a future.

In 1.21.4 the client thread and the render thread are the same thread. Minecraft's `BlockableEventLoop` (the `Minecraft` instance) accepts tasks through `execute` and `submit`. MCDT does not use those directly for request work, because an unbounded flood of requests would stall a frame. Instead it runs its own bounded queue:

1. A Jetty worker thread parses the request, checks auth, and validates input. No game access happens here.
2. The handler builds a `GameTask` (a closure plus a deadline) and offers it to the `TaskQueue`. The queue has a fixed capacity. A full queue returns `503 Service Unavailable` with `Retry-After`.
3. At the end of each client tick, a hook drains the queue until a time budget runs out (default 2 ms per tick, configurable). Tasks left over wait for the next tick.
4. Each task runs, serializes its result to a `JsonElement` on the client thread (so no game object escapes), and completes its future.
5. The worker thread writes the response. If the deadline passes first (default 5 s), the request returns `504 Gateway Timeout` and the task is cancelled if it has not started.

Rules that follow from this model:

- Serialization happens on the client thread, because game objects are not thread-safe. Large reads must therefore be paginated to stay inside the budget.
- Work that must happen during rendering, such as screenshots, uses a render hook instead of the tick hook, under the same budget and future mechanism.
- Event streaming runs the other way: game hooks write compact event records into a lock-free ring buffer, and a dispatcher thread fans them out to subscribers. A slow subscriber drops events and receives a `gap` message; it never back-pressures the game.
- Input injection (keys, mouse) is queued and applied at a defined point in the tick, so scripted input is deterministic relative to game ticks.

## API design

The API has four layers: curated endpoints with stable schemas, high-level automation through Baritone (next section), a reflection layer that reaches any object, and an event stream. All paths sit under `/v1`, and breaking changes go to `/v2`.

### Layer 1: curated endpoints

Stable, documented, version-independent JSON. Most users only need this layer.

| Method and path | Purpose |
| --- | --- |
| `GET /v1/health` | Mod version, game version, uptime, queue depth |
| `GET /v1/client` | FPS, window size, current screen, connection state (menu, singleplayer, multiplayer) |
| `GET /v1/player` | Position, rotation, velocity, health, hunger, XP, game mode, effects |
| `GET /v1/player/inventory` | All slots with item id, count, components |
| `GET /v1/world` | Dimension, time, weather, loaded chunk count |
| `GET /v1/world/blocks?from=x,y,z&to=x,y,z` | Block states in a box, capped at 32,768 blocks per call |
| `GET /v1/world/entities?radius=r&type=id` | Entities near the player, filterable |
| `GET /v1/screen` | Open screen class, title, widgets, slots |
| `GET /v1/registries/{registry}` | Entries in a registry such as `minecraft:item` |
| `GET /v1/options` | Game options |
| `PATCH /v1/options` | Change options (write) |
| `POST /v1/chat` | Send a chat message or command (write) |
| `POST /v1/input/keys` | Press, release, or tap a key mapping or raw key (write) |
| `POST /v1/input/mouse` | Move, click, scroll (write) |
| `POST /v1/player/look` | Set yaw and pitch (write) |
| `POST /v1/screen/actions` | Click a widget or slot, close a screen (write) |
| `GET /v1/screenshot` | PNG of the current frame, optional scale |

### Layer 2: reflection

This layer delivers "the entirety of Minecraft". It walks the live object graph starting from named roots.

| Method and path | Purpose |
| --- | --- |
| `GET /v1/objects/roots` | Root handles: `minecraft`, `player`, `level`, `screen`, `options`, `gameRenderer` |
| `GET /v1/objects/{handle}` | Class, fields with values (depth-limited), methods |
| `GET /v1/objects/{handle}/fields/{name}` | One field, expanded |
| `PUT /v1/objects/{handle}/fields/{name}` | Set a field (write, off by default) |
| `POST /v1/objects/{handle}/invoke` | Call a method with JSON arguments (write, off by default) |
| `GET /v1/classes/{name}` | Static fields and methods of a class |
| `DELETE /v1/objects/{handle}` | Release a handle early |

A path query avoids round trips, for example `GET /v1/objects/minecraft?path=player.getInventory().items[0]`. Paths allow field access, zero-argument getters, and list or array indexing only.

### Layer 3: events

`GET /v1/events` upgrades to a WebSocket. The client sends a subscribe message with topics and optional filters; the server streams JSON events.

Initial topics: `tick`, `chat`, `screen.open`, `screen.close`, `world.join`, `world.leave`, `player.damage`, `player.death`, `entity.spawn`, `entity.remove`, `block.update`, `packet.in` (packet class name only by default).

### Conventions

- Errors use RFC 9457 problem JSON with a stable `type` code.
- Coordinates are integers for blocks and doubles for entities.
- Identifiers are strings like `minecraft:diamond_sword`.
- Text components serialize to both plain text and component JSON.
- Every response carries the game tick it was read at in an `X-MCDT-Tick` header.

### Documentation at the root

`GET /` serves the API documentation, so opening `http://127.0.0.1:<port>/` in a browser is the first thing a developer does.

| Path | Serves |
| --- | --- |
| `/` | Scalar: API reference, request client with bearer-token auth, and code samples per endpoint (curl, Python, JavaScript, and more) |
| `/redoc` | ReDoc reference view, kept as a lightweight alternative |
| `/openapi.json` | The OpenAPI 3.1 spec, generated from handler annotations |

The docs pages and the spec need no token, because they reveal nothing about the running game. Every `/v1` route still requires it. Scalar's browser build ships inside the jar, pinned to one version, so the docs work offline. It calls the API from the same origin with no request proxy configured, so the token never leaves the machine, and MCDT's own origin is always on the Origin allowlist.

## Coverage: everything a client can do

MCDT's contract is parity with the client: anything a player can do with keyboard and mouse, and anything the client can send over the network, a tool can do through the API. Four guarantees back that contract, from most convenient to most complete.

1. **Curated endpoints** for every common action, with stable schemas (the table below).
2. **Real input injection.** Synthetic key, character, mouse button, move, and scroll events enter at the same handlers GLFW calls (`KeyboardHandler`, `MouseHandler`). The game cannot tell them from hardware, so any action a human can perform works, including in screens MCDT has never seen. Injected input works while the window is unfocused: MCDT keeps the mouse grab state and suppresses pause-on-focus-loss while a tool is driving.
3. **Raw packets.** `POST /v1/packets` builds any serverbound packet from JSON (most 1.21.4 packets are records, so fields map directly to constructor arguments) and sends it through the live connection. Every network action a client can take is a serverbound packet, so this layer is complete by construction. Clientbound packets stream on the `packet.in` topic, with full payloads when requested.
4. **Reflection** for anything left: client-only behaviour that is neither input nor network, such as internal state, renderers, and caches.

### Curated coverage

| Area | What a tool can do | Endpoints |
| --- | --- | --- |
| Session | Title screen, open or create a singleplayer world, connect to a server by address, disconnect, open to LAN, pause, quit the game (two-step confirm, below) | `/v1/session`, `/v1/worlds`, `/v1/servers` |
| Movement | Hold or tap forward, back, strafe, jump, sneak, sprint for N ticks or until stopped; look; ride and dismount; fly in creative and spectator | `/v1/player/move`, `/v1/player/look` |
| Interaction | Attack, use item, interact with a block or entity (by position, face, or handle), stop using, swing, pick block, swap hands, select hotbar slot, drop item or stack, respawn | `/v1/player/actions` |
| Inventory and containers | Every slot click type (pickup, quick move, swap, clone, throw, quick craft, pickup all), creative inventory, recipe book, crafting, furnaces, anvils, enchanting, villager trades, beacons, any modded container | `/v1/screen/slots`, `/v1/recipes` |
| Screens and text | Widget tree of any open screen; click, toggle, slide, scroll; type into text fields; edit signs and books; close or open screens | `/v1/screen`, `/v1/screen/actions` |
| Chat | Send messages and commands, read history, command suggestions | `/v1/chat` |
| HUD | Read action bar, titles, boss bars, scoreboard sidebar, tab list, toasts, subtitles, held item | `/v1/hud` |
| Targeting | What the crosshair hits, raycasts from any point, entities in view | `/v1/world/raycast` |
| World | Blocks, block entities, entities, biomes, light, time, weather, chunks | `/v1/world/*` |
| Player state | Health, food, XP, effects, abilities, stats, advancements | `/v1/player`, `/v1/stats`, `/v1/advancements` |
| Camera and window | Perspective, FOV, window size, fullscreen, focus, screenshots | `/v1/camera`, `/v1/window`, `/v1/screenshot` |
| Settings | All options, keybinds, resource packs (enable, reorder, reload), languages, sound volumes | `/v1/options`, `/v1/keybinds`, `/v1/resourcepacks` |
| Debug | F3 data, hitboxes, chunk borders, debug renderers, profiler snapshot | `/v1/debug` |
| Server list | Add, edit, remove, reorder servers, ping status | `/v1/servers` |

### Quitting the game: a two-step confirm

Quitting stops the game, and with it the web server, so a single stray request must never trigger it. Quit is a two-step call with a short-lived confirmation token.

1. `POST /v1/session/quit` returns `202 Accepted` with `{ "confirmToken": "<uuid>", "expiresAt": "<time>" }`. Nothing happens to the game yet.
2. `POST /v1/session/quit/confirm` with `{ "confirmToken": "<uuid>" }` returns `200 OK`, flushes the response, then shuts the game down cleanly after a short delay so the reply reaches the caller.

Rules:

- The token is a random UUID, single-use, valid for 30 seconds, and bound to the bearer token that requested it.
- An unknown, expired, or reused token returns `409 Conflict` with problem type `quit-token-invalid`; the caller must start again from step 1.
- On confirm, MCDT sends WebSocket clients a close frame with code 1001 (going away), deletes its discovery file, and saves the world if one is open, the same as quitting from the menu.
- Quitting can be disabled entirely with the `allowQuit` setting. When disabled, both calls return `403 Forbidden` with problem type `quit-disabled`, and the endpoints are marked as such in the docs.
- Quit needs the `act` permission. In the MCP server it is two tools, `quit_request` and `quit_confirm`, with the same token flow.

The same confirm pattern applies to the other irreversible client actions: deleting a singleplayer world and removing a server-list entry.

### Proving coverage

Parity is tested, not assumed. A CI job launches the client in a headless test world and checks three lists against the API:

- Every registered `KeyMapping` (vanilla and modded) can be pressed through `/v1/input/keys`.
- Every serverbound packet type can be built and sent through `/v1/packets`.
- Every vanilla `Screen` subclass can be opened, read, and operated through `/v1/screen`.

A gap in any list fails the build, so new game versions cannot silently reduce coverage.

## Structured view: what the player sees

`GET /v1/view/blocks` returns the blocks actually visible from the camera as JSON: a "screenshot" made of block data instead of pixels. Blocks hidden behind other blocks, outside the field of view, or beyond the distance limit are culled. Models without vision can then reason about the scene directly, and vision models get exact ids and coordinates to go with `/v1/screenshot`.

### How visibility is computed

MCDT casts a grid of rays from the camera through the view frustum, using the current FOV and aspect ratio, and walks each ray voxel by voxel (DDA traversal):

1. A ray stops at the first opaque block it hits. That block, and the face it hit, are recorded as visible.
2. Non-occluding blocks (glass, leaves, water, flowers, rails) are recorded and the ray continues through them, as the eye does.
3. Rays that reach the distance limit without a hit count as sky.

The ray grid is the resolution of the view: 128 by 72 rays by default, up to 512 by 288. A block hit by more rays covers more of the screen, so each block also carries its screen coverage.

Tracing is too slow for the 2 ms tick budget, so it runs in two phases. On the client thread, MCDT copies the chunk sections inside the frustum and distance limit (cheap palette copies, spread across ticks if needed). A worker thread then traces the rays against that snapshot, so the game never stalls.

### Parameters

| Parameter | Default | Meaning |
| --- | --- | --- |
| `res` | `128x72` | Ray grid size, up to `512x288` |
| `maxDistance` | `64` | Blocks; capped at render distance |
| `fluids` | `true` | Record water and lava surfaces |
| `passThrough` | `true` | Record non-occluding blocks and continue the ray |
| `entities` | `false` | Also record entities whose hitboxes the rays hit |
| `grid` | `false` | Also return the label grid and depth grid (below) |
| `minCoverage` | `0` | Drop blocks covering less than this fraction of the screen |

### Response

Block states go into a palette so repeated states are sent once. Blocks are sorted by screen coverage, largest first.

```json
{
  "tick": 182044,
  "camera": { "pos": [104.5, 65.62, -201.3], "yaw": 91.2, "pitch": 12.0, "fov": 70 },
  "res": [128, 72],
  "palette": ["minecraft:grass_block[snowy=false]", "minecraft:oak_log[axis=y]", "minecraft:water[level=0]"],
  "blocks": [
    { "pos": [110, 64, -199], "p": 0, "faces": ["up"], "distance": 6.1, "coverage": 0.084 },
    { "pos": [118, 66, -196], "p": 1, "faces": ["west", "north"], "distance": 14.3, "coverage": 0.012 }
  ],
  "entities": [],
  "sky": 0.31
}
```

With `grid=true`, the response adds `labels` (one palette index per ray, -1 for sky) and `depth` (distance per ray, in blocks). Together they form a coarse labelled depth image of the scene, row by row, that a text-only model can scan like a picture.

### Scope

- Read-only, so it needs only the `read` permission. A settings toggle turns the endpoint off, and the settings screen sets the maximum `res`.
- `GET /v1/view/blocks/summary` returns counts per block type, nearest instance of each, and the crosshair target: a cheap overview before asking for the full view.
- In the MCP server it becomes the `view_blocks` tool, likely the tool non-vision agents call most.

## Baritone integration

MCDT treats Baritone as an optional mod: if the user installs Baritone 1.13.1 (the official 1.21.4 release), the goal-level endpoints turn on; if not, they return a clear "Baritone not installed" error. A tool can then ask for "mine 64 iron ore" or "go to 100 64 -200" instead of scripting every key press.

The curated input endpoints work at the level of keys and clicks. Baritone works at the level of goals: it plans paths, breaks and places blocks, and recovers when the world changes. The two layers complement each other, and both are useful to tools and AI agents.

### Packaging

- Compile against `baritone-api-fabric` 1.13.1 as `modCompileOnly`. MCDT does not ship Baritone.
- List Baritone under `suggests` in `fabric.mod.json`, not `depends`, so MCDT loads without it.
- At startup, check `FabricLoader.getInstance().isModLoaded("baritone")` and that `baritone.api.BaritoneAPI` resolves. Only then construct `BaritoneBridge`.
- Keep every Baritone import inside the bridge package. The JVM loads classes lazily, so code that never touches the bridge never fails with `NoClassDefFoundError` when Baritone is absent.
- Users need the `api-fabric` jar. The `standalone-fabric` jar strips the API classes; if Baritone is loaded but the API class is missing, MCDT logs a warning naming the right jar.
- Code only against the `baritone.api` package. Baritone does not support use of anything outside it.

When Baritone is missing:

- Every `/v1/baritone/*` route returns `501 Not Implemented` with problem JSON, `type` `baritone-not-installed`, and a `detail` telling the user which jar to install.
- `GET /v1/health` and the landing data report `baritone: not-installed` or `baritone: 1.13.1`.
- The Baritone endpoints stay in the OpenAPI spec under an "Baritone (optional)" tag, so the docs are the same either way.
- The `baritone.*` event topics accept subscriptions but never emit.

### Access path and threading

`BaritoneAPI.getProvider().getPrimaryBaritone()` returns an `IBaritone`. It gives the processes (custom goal, mine, follow, explore, farm, builder, get-to-block), the pathing behaviour, and the command manager. `BaritoneAPI.getSettings()` holds all settings. `BaritoneBridge` makes every call through the task queue on the client thread. Baritone calculates paths on its own background threads, so these calls use little of the tick budget.

### Endpoints

| Method and path | Purpose |
| --- | --- |
| `GET /v1/baritone` | Active process, current goal, pathing state |
| `GET /v1/baritone/path` | Current and next path segments as block positions, for overlays |
| `POST /v1/baritone/goto` | Goal by block `{x, y, z}`, column `{x, z}`, area `{x, y, z, radius}`, or nearest block of a type |
| `POST /v1/baritone/mine` | Block ids and an optional quantity |
| `POST /v1/baritone/follow` | Players or entities by name, UUID, or handle |
| `POST /v1/baritone/explore` | Explore outward from a point |
| `POST /v1/baritone/farm` | Harvest and replant within a range |
| `POST /v1/baritone/build` | Build a schematic from the schematics folder at an origin |
| `POST /v1/baritone/cancel` | Stop all processes and pathing |
| `GET /v1/baritone/settings` | Every setting with type, default, and current value |
| `PATCH /v1/baritone/settings` | Change settings |
| `POST /v1/baritone/command` | Run a raw Baritone command such as `goto 100 64 -200` |

Goals take time, so goal endpoints return `202 Accepted` with a job id. `GET /v1/baritone/jobs/{id}` reports status, and completion or failure also arrives as an event.

New event topics: `baritone.path` (calculation started, executing, at goal, cancelled, failed) and `baritone.process` (active process changed). Both come from Baritone's own event bus.

### Safety and licence

- Every non-GET Baritone endpoint needs the `act` permission. In multiplayer it also needs the same per-server opt-in as other writes, because many servers ban Baritone.
- Baritone's own safety settings (`allowBreak`, `allowPlace`, and so on) are exposed unchanged; MCDT does not override them.
- MCDT does not redistribute Baritone; the user installs it. Baritone's LGPL-3.0 licence therefore places no distribution obligations on MCDT, which stays free to use any licence.

Source: [Baritone releases](https://github.com/cabaletta/baritone/releases).

## Serialization and object handles

Objects are serialized to a fixed depth, anything deeper becomes a handle, and runtime class names are translated from intermediary to Mojang names using a mappings file the user's own client downloads.

### Runtime names: the problem compile-time mappings do not solve

Mojang mappings apply only at development time. In a production Fabric install, the game runs with intermediary names such as `net.minecraft.class_310` and `method_1551`. Plain reflection in a released mod therefore shows those names, not `Minecraft` and `getInstance`. Fabric's `MappingResolver` translates only between official (obfuscated) and intermediary in production.

MCDT resolves this with a `NameService`:

1. On first use of the reflection layer, MCDT asks the user to opt in, then downloads the official 1.21.4 client mappings from Mojang's own version manifest to the local config folder. The user's machine fetches them from Mojang directly, so the mod never redistributes them.
2. It combines those with Fabric's intermediary mappings, which are available at runtime, to build an intermediary-to-Mojang table for classes, fields, and methods.
3. Reflection responses show Mojang names, and the reflection endpoints accept them as input.
4. Without the file, the API falls back to intermediary names. Curated endpoints never depend on it, because their schemas are hand-written.

The mapping table stays in memory and in the user's config folder. No endpoint returns the table or mapping data in bulk.

### Value encoding

- Primitives, strings, enums, and boxed types serialize as JSON values.
- Known game types use type adapters: `Vec3`, `BlockPos`, `ResourceLocation`, `ItemStack`, `Component`, `BlockState`, `Entity` (summary), `UUID`.
- Collections and arrays serialize their first 64 elements plus a total `size`; the rest is reached through paging.
- Other objects serialize as `{ "$handle": "h_8f3a", "$class": "net.minecraft.client.Minecraft" }` once the depth limit (default 1) is hit.
- Cycles become handles, never infinite recursion.

### Handles

- A handle is a short random ID mapped to a `WeakReference` in a `HandleTable`. MCDT never keeps a game object alive.
- A handle whose object was collected returns `410 Gone`.
- Handles expire after 10 minutes without use and are cleared on world leave.
- The table is capped at 50,000 entries; the least recently used entries are evicted first.
- Method invocation picks an overload by parameter count and JSON argument types. Arguments may be primitives, strings, handles, or adapter-typed objects such as `{ "x": 1, "y": 64, "z": 1 }` for `BlockPos`.

## Security model

The reflection layer can call any method in the JVM, so MCDT treats every request as untrusted: loopback only, a per-launch token, browser defences, and writes off until the user turns them on.

| Threat | Defence |
| --- | --- |
| Other machines on the network | Bind to `127.0.0.1` only by default. Binding elsewhere requires an explicit config value and shows an in-game warning. |
| Other local processes | A random 256-bit token is generated each launch and written to `config/mcdt/token`. Every request needs `Authorization: Bearer <token>`. Comparison is constant-time. |
| Malicious web pages (CSRF) | Requests with an `Origin` header are rejected unless the origin is on a configured allowlist. No CORS by default. Tokens are never accepted in query strings. |
| DNS rebinding | The `Host` header must be `127.0.0.1:<port>` or `localhost:<port>`. |
| Unintended writes | Three permission levels in config: `read` (default), `act` (input, chat, options, screen actions), `reflect-write` (field set, method invoke, and raw packet send). |
| Dangerous reflection targets | A denylist blocks `java.lang.Runtime`, `ProcessBuilder`, `System.exit`, class loaders, `java.lang.reflect`, file and network APIs, and `Unsafe`, even with `reflect-write` on. |
| Session token leakage | The player's access token and the user object are redacted in every response. |
| Resource exhaustion | Bounded task queue, per-tick time budget, request size limit (1 MB), rate limit per token. |

User-visible signals:

- A small HUD indicator shows when a client is connected and flashes on write requests.
- A keybind toggles the API on and off instantly.
- In multiplayer, `act` and `reflect-write` are disabled unless the user enables them per server, and the docs warn that automation can break server rules.

## Configuration and discovery

Tools find a running client through discovery files, so the port never needs to be hard-coded, and every setting lives in an in-game settings screen.

### Port and discovery

- MCDT tries a preferred port (default 25580, configurable). If it is taken, it binds a random free port instead, so several clients can run side by side.
- On start, MCDT writes one discovery file per running client to `~/.mcdt/instances/<pid>.json` (`%USERPROFILE%\.mcdt\instances\` on Windows). A copy also goes to `config/mcdt/instance.json` in the game directory.
- The file holds: base URL, port, process id, MCDT version, game version, game directory, player name, start time, and the bearer token.
- The file is readable only by the current user (mode 0600 on Linux and macOS; user-profile permissions on Windows), the same protection as the token file.
- MCDT deletes its file on clean shutdown. After a crash, a tool detects a stale file because the process id is gone or `GET /v1/health` does not answer.
- The environment variable `MCDT_PORT` overrides the preferred port, for CI and scripted launches.
- On world load, a chat line shows the URL with a click-to-open link to the docs at `/`.

A tool's discovery logic is short: list `~/.mcdt/instances/`, read each file, call `/v1/health` with the token, and pick the client by player name or game directory.

### Settings screen

An in-game screen, opened from Mod Menu or a keybind, manages every setting. It is built with YACL (Yet Another Config Lib), and Mod Menu is an optional dependency.

| Group | Settings |
| --- | --- |
| Server | Enabled, preferred port, bind address (with a warning when not loopback), request timeout, allow quit |
| Access | Default permission level (`read`, `act`, `reflect-write`), Origin allowlist, regenerate token, copy URL and token |
| Multiplayer | Per-server opt-in list for `act` and `reflect-write` |
| Performance | Per-tick budget, queue size, handle table size, event topics allowed |
| Baritone | Status (installed version or not installed), enable Baritone endpoints |
| Interface | HUD indicator, chat message on world load, open docs in browser |

Most changes apply immediately. Changing the port or bind address restarts the HTTP server in place and rewrites the discovery file; open WebSocket clients receive a close frame with code 1012 (service restart). Settings are stored in `config/mcdt/config.json`, so they can also be edited by hand or checked into a modpack.

## Performance considerations

The hard target is that MCDT idle costs nothing measurable and MCDT under load costs at most its per-tick budget (2 ms by default, out of a 50 ms tick).

- **Idle cost.** No polling. Hooks for event topics register only while at least one subscriber wants that topic.
- **Reflection metadata.** Field and method lists per class are computed once and cached, including resolved names and `MethodHandle`s. `MethodHandle` calls are faster than `Method.invoke` after warm-up.
- **Bulk reads.** Block queries read chunk sections directly and are capped per call. Entity queries use the level's spatial lookup instead of scanning every entity.
- **Screenshots.** Capture on the render thread, encode the PNG on a worker thread. The default output is scaled to half resolution.
- **Allocation.** Event records use a preallocated ring buffer to avoid garbage on the game thread.
- **Batching.** `POST /v1/batch` runs up to 32 read operations in one task, so they read from the same tick and cost one queue slot.
- **Measurement.** `GET /v1/health` reports queue depth, average and p99 task time, and dropped events. A benchmark mod config runs a fixed request load against a test world in CI.

## Implementation roadmap

- **M0 – Scaffold (Completed):** Build setup (Loom, Parchment mappings, Javalin 7 on Jetty 12, Jackson, jar-in-jar packaging), config store, token generation, owner-only discovery files, loopback HTTP server with Host/Origin/Bearer/RateLimit/Size filters, bounded TaskQueue drained on client tick under time budget, problem-JSON RFC 9457 error handling, `GET /v1/health`, offline Scalar docs at `/`, ReDoc at `/redoc`, and `/openapi.json`.
- **M1 – Read-Only Client & World:** Curated read endpoints (`/v1/client`, `/v1/player`, `/v1/player/inventory`, `/v1/world`, `/v1/world/blocks`, `/v1/world/entities`, `/v1/screen`, `/v1/registries`, `/v1/options`), raycasting, and screenshot capture.
- **M2 – Actions & Real Input:** Synthetic keyboard and mouse input injection, chat/commands, player look/move, screen slot actions, two-step confirmation flows.
- **M3 – Reflection Layer (Read-Only):** Walk object graph from named roots, NameService (Mojang mappings download), HandleTable, path traversal.
- **M4 – Event Streaming & Realtime Input (WebSocket) (Completed):** `/v1/events` WebSocket endpoint with lock-free ring buffer fan-out, clientbound packet stream, realtime input channels (`/v1/input/keys`, `/v1/input/mouse`), inventory stream (`/v1/player/inventory`), player status stream (`/v1/player/status`), and binary 3D viewer world stream (`/v1/world/stream`).
- **M5 – High-Level Control & Writes (Completed):** Reflection writes (`setField`, `invoke`), raw packet send, Baritone integration (goals, pathing, mining, following).
- **M6 – Model Context Protocol (MCP) (Completed):** Streamable HTTP MCP server at `/mcp` and stdio shim for LLM agents.

## MCP server (after v1)

Once the REST API is frozen as v1, MCDT adds a Model Context Protocol server so AI agents can operate the client directly. It is a thin adapter over the same handlers, so it adds no new game logic and inherits the threading, permissions, and security model.

### Transport

- **Streamable HTTP at `/mcp`** on the same Javalin server and port. Agents that support remote MCP servers connect with the same bearer token as the REST API.
- **A stdio shim** for clients that only launch local processes: a small launcher (`mcdt-mcp`) that reads the discovery files, picks a running client, and relays stdio to `/mcp`. It is a separate download, not part of the mod.
- Implementation uses the official MCP Java SDK if it fits Javalin cleanly; otherwise a hand-written JSON-RPC layer, since the protocol surface is small.

### What it exposes

| MCP concept | MCDT mapping |
| --- | --- |
| Tools | A curated, agent-sized set rather than one tool per REST route: `get_state`, `look`, `move`, `interact`, `inventory_click`, `screen_action`, `chat`, `screenshot`, `raycast`, plus `baritone_goto`, `baritone_mine`, `baritone_follow`, `baritone_cancel` when Baritone is installed |
| Escape hatches | `rest_call` (any `/v1` route) and `inspect` (reflection), so an agent is never limited to the curated set |
| Resources | `mcdt://player`, `mcdt://inventory`, `mcdt://screen`, `mcdt://world`, `mcdt://hud`, with subscriptions mapped to the event stream |
| Images | `screenshot` returns MCP image content, so vision-capable models can see the game |
| Notifications | Resource-updated notifications driven by the ring buffer, rate-limited per session |

### Rules

- Tool schemas are generated from the same annotated handler types as the OpenAPI spec, so the two never drift.
- Tool descriptions are written for models: what the tool does, units, coordinate conventions, and what to call next.
- Long actions (Baritone goals, timed movement) return a job id immediately; the agent polls `job_status` or watches a resource, so no tool call blocks for minutes.
- Every tool call respects the permission level. A `read`-only configuration exposes only read tools, so an agent never sees tools it cannot use.
- The settings screen gets an MCP group: enable or disable, and a log of recent tool calls.

## Risks and open questions

The largest risks are the runtime name translation and porting cost across game versions; both are contained by keeping curated endpoints independent of the reflection layer.

**Risks**

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Mapping download is unavailable or its terms change | Reflection shows intermediary names | Fall back cleanly; curated layer unaffected |
| Baritone API changes or lags new game versions | Automation endpoints break on update | Pin the version, isolate it behind BaritoneBridge, return 501 if absent |
| Reflection writes crash the game | Lost world state | Off by default, denylist, exceptions caught and returned as errors |
| Port to newer versions breaks mixins and adapters | Delayed releases | Keep mixins few; curated layer isolates schema from internals |
| Servers treat MCDT as a cheat client | Bans for users | Write permissions off in multiplayer by default; clear docs |
| Slow clients or heavy reads cause lag | Bad player experience | Per-tick budget, queue cap, pagination |

**Open questions**

- [ ] Confirm Baritone's Fabric mod id is `baritone` in 1.13.1, and test detection of the `standalone-fabric` jar.
- [ ] Is a Unix socket or Windows named pipe worth offering as a safer transport than TCP?
- [ ] Should reflection expose private fields by default, or only public plus getters?
- [ ] Licence for MCDT itself (MIT, Apache-2.0, or LGPL)?
