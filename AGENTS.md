# AGENTS.md

Guide for coding agents working in this repo. Read it before you change code.

## What this is

MCDT (Minecraft DevTools) is a **client-only mod for Fabric and NeoForge**. Mod id: `mcdt`. Targets: Minecraft 1.21.1 and 1.21.4 on both loaders (four Stonecutter nodes), Java 21, Mojang mappings with a Parchment layer. The mod runs a Javalin 7 / Jetty 12 server inside the game on `127.0.0.1:25580` (it falls back to a random port if that one is taken). The server exposes the live client over REST, WebSocket, SSE, and an MCP server at `/mcp`. A Svelte 5 WebUI (in `web/`) is served at `/`.

What each document is for:

- `README.md`: the user-facing feature list.
- `MCDT.md`: the design doc and roadmap. **Parts of it are not built yet**, for example `/v1/view/blocks`, `/v1/hud`, `/v1/batch`, `/v1/worlds`, and `/v1/servers`.
- `src/main/java/dev/advik/mcdt/http/HttpServer.java`: **the source of truth for which routes exist**. Check it before you trust either doc.

## Layout

Gradle projects: `core/`, `server/`, and one platform project per Stonecutter node (`:1.21.4-fabric`, `:1.21.1-neoforge`, ...),
all built from the shared `src/`. Dependencies only point down: platform -> `server/` -> `core/`. Packages such as `http` or
`game` are spread over several projects; each node builds one mod jar (see invariant 6).

```
core/src/main/java/dev/advik/mcdt/      NO Minecraft, loader or Javalin types; Java 17 (--release 17)
  Mcdt.java, McdtEnvironment.java       mod id + logger; launch environment record
  api/v1/dto/                           request/response records (also drive the OpenAPI schema)
  game/GameClient                       the seam between the server and the game (tests mock it)
  exec/                                 TaskQueue, GameTask, TickClock: bounded queue drained on the client thread
  http/Problem(s), RequestLogWriter     RFC 9457 problems, request log
  events/                               EventHub (+ EventSink transport seam), ring buffer, dispatcher
  reflection/                           HandleTable, NameService, PathEvaluator, ReflectionSecurity, serializer;
                                        SPIs the platform implements: MappingProvider, GameTypeAdapter, SessionClassProvider
  decompiler/                           Vineflower host (isolated loader), MojangRemapper (ASM), ModResourceSource SPI
  mcp/                                  McpToolRegistry, McpResourceRegistry (tool logic), mcp/dto
  baritone/                             BaritoneService, UnavailableBaritoneService, BaritoneJobManager
  config/, discovery/, security/, json/, world/stream/
core/src/testFixtures/java/             MockGameClient, MockBaritoneService (shared by every project's tests)
server/src/main/java/dev/advik/mcdt/    Javalin 7 / Jetty 12; Java 17; no Minecraft or loader types
  McdtCore.java                         composition root (config, token, queue, server, discovery)
  api/v1/                               one Handler class per route group, annotated with @OpenApi
  http/                                 HttpServer (routes), WebSocket handlers, WsEventSink
  security/                             AccessFilter (bearer, Host, Origin, rate limit, role + multiplayer), McdtRole
  mcp/                                  McpHandler, McpSession, McpSessionManager (JSON-RPC transport)
  docs/                                 Scalar / ReDoc / WebUI static routes
src/main/java/dev/advik/mcdt/           the platform, shared by every node; the only place with Minecraft types
  client/                               McdtClient (loader-neutral lifecycle: start, tick drains the queue, events), YACL config screen
  game/                                 MinecraftGameClient, MinecraftTypeAdapter, MinecraftSessionClass, serializers
  platform/fabric/                      `//? if fabric`: FabricEntrypoint, Mod Menu, FabricMappingProvider, FabricModResourceSource
  platform/neoforge/                    `//? if neoforge`: NeoForgeMod (@Mod entrypoint), config screen, mapping + resource SPIs
  mixin/                                mixins + accessors, registered in src/main/resources/mcdt.mixins.json
  baritone/bridge/                      the only Baritone-aware code
src/main/resources/META-INF/services/   registers the shared SPI implementations (ServiceLoader)
src/fabric/resources/                   fabric.mod.json and the Fabric-only services; src/neoforge/resources/ the same for NeoForge
versions/<node>/gradle.properties       per-node versions (Minecraft, loader API, Parchment, YACL, Mod Menu, Baritone + SHA, Java)
settings.gradle.kts, stonecutter.gradle.kts  the node list; the Stonecutter controller (collectJars, constants)
build.fabric.gradle.kts, build.neoforge.gradle.kts  the per-loader node build scripts (Loom, ModDevGradle)
buildSrc/                               DownloadVerified task (pinned-SHA downloads); McdtBuild (jar-in-jar walk, Baritone jars)
tests/live/                             Python live suite (uv + pytest) run against a real client
web/                                    Svelte 5 + Vite + Tailwind 4 + daisyUI 5 dashboard (Bun)
scripts/                                MCP stdio shim (mcdt-mcp.py / .ps1); ci/live-client.sh (CI live tests), ci/node-env.sh (node facts)
docker/                                 entrypoint.sh and setup/ (the :docker-setup project: mod install at container start)
http/                                   .http request samples per area
libs/                                   pinned Baritone API jars, one per loader and version (committed via .gitignore exceptions)
docs/MULTIVERSION.md                    the multi-version / multi-loader plan and its progress
spikes/                                 throwaway Phase 0 experiments (not shipped)
```

## Build, run, test

- `./gradlew build` (`gradlew.bat` on Windows) builds every node and copies each mod jar to `build/libs/mcdt-<version>+<mc>-<loader>.jar` (`collectJar`). One node: `./gradlew :1.21.1-neoforge:build`. **Bun must be on PATH**: the server project's `installWeb` → `buildWeb` → `syncWebAssets` run `bun install --frozen-lockfile` and `bun run build`, and the output is packed into the jar.
- `./gradlew test` runs JUnit 5 in `core`, `server` and every node, then the JaCoCo reports. To run one class: `./gradlew :server:test --tests 'dev.advik.mcdt.mcp.McpToolsTest'` (`:core:test`, or `:1.21.4-fabric:test` for a node).
- `./gradlew :1.21.4-fabric:runClient` (or another node) starts a dev client in `run/`. On Fabric, YACL and Mod Menu are on the dev runtime (`modLocalRuntime`). Baritone is not.
- A cold Gradle cache needs network access *at configure time*. `McdtBuild.baritoneJar` (buildSrc) verifies each node's Baritone jar in `libs/` against the SHA-256 pinned in `versions/<node>/gradle.properties` and downloads it again if the check fails. Scalar is downloaded the same way, pinned by version and SHA.
- `core` and `server` compile with `--release 17`; each node with its `java_version` (21 for now). CI (`.github/workflows/build.yml`) runs `./gradlew build` on ubuntu with JDK 25 (Microsoft) and Bun. The Gradle configuration cache is on.
- `runClient` serves the dashboard straight from `web/dist` (Gradle passes `-Dmcdt.webDist`), so `cd web && bun run build` followed by a browser reload picks up UI changes without restarting the game.
- WebUI dev: run `cd web && bun run dev` (Vite on :5173). It proxies `/v1`, `/mcp`, `/docs`, and `/openapi.json` to :25580. Add `http://localhost:5173` to `originAllowlist` in the config, or the API rejects the Origin. Type-check with `bun run check`.
- Shared versions (Fabric Loader, Loom, ModDevGradle, Shadow, Javalin, Jackson, ...) live in `gradle.properties`; per-node ones (Minecraft, Fabric API or NeoForge, Parchment, YACL, Mod Menu, Baritone + SHA-256, Java) in `versions/<node>/gradle.properties`.
- Docker: `Dockerfile` builds one image per node (`--build-arg NODE=1.21.1-neoforge`; default `1.21.4-fabric`) with Xvfb, Mesa and the native portablemc binary. There is no Python in the image. The node's facts (loader, launcher target, pinned Fabric API URL + SHA-512, Baritone jar) come from `versions/<node>/gradle.properties` through `scripts/ci/node-env.sh`, so a version bump reaches the image automatically. The image holds **no Minecraft files** (the EULA forbids publishing them): the game downloads into `/data/.portablemc` on first start. `docker/entrypoint.sh` runs `docker/setup` (the `:docker-setup` Java project) before the launch. It installs the image's jars and the user's mods (`/mods`, `MC_MODS`, `MC_MODRINTH`), tracks them in `/data/mods/.mcdt-managed`, and leaves other jars alone. `.github/workflows/docker.yml` live-tests every image and publishes `ghcr.io/advik-b/mcdt-<node>` (`:edge` from master, `:<version>` + `:latest` from `v*` tags). `MCDT_*` environment overrides are applied in `ConfigStore.applyEnv`. `MCDT_TOKEN` is handled in `McdtCore`.

## Stonecutter (versions and loaders)

- Nodes are `<mc>-<loader>` (`1.21.1-fabric`, `1.21.4-neoforge`, ...), listed in `settings.gradle.kts`. Each node is a Gradle project in `versions/<node>/` built by `build.<loader>.gradle.kts` from the shared `src/`.
- `src/` always holds the **active** node's code (`.sc_active_version`; the committed one is `1.21.4-fabric`). Other nodes compile from processed copies under `versions/<node>/build/generated/stonecutter/`.
- Switch with `./gradlew "Set active project to 1.21.1-neoforge"` (rewrites the comments in `src/`), and switch back with `./gradlew "Reset active project"` **before you commit**.
- Conditional code: `//? if fabric {` ... `//?}`, `//? if >=1.21.2 {` ... `//?} else {` ... `//?}`. Inactive branches are block-commented (`/*` ... `*///?}`). Write both branches as plain code, then run `./gradlew "Refresh active project"` to comment the inactive ones.
- Loader-only code lives in `platform/<loader>/`, each file wrapped whole in `//? if <loader> {`. Loader-only resources (mod metadata, SPI service files) live in `src/<loader>/resources/`. Small version differences use inline conditionals (see `ToastManagerAccessor`, `ViewerWorld`).
- Add a node: a `version(...)` line in `settings.gradle.kts`; `versions/<node>/gradle.properties` (copy a sibling; pin the Baritone jar's SHA-256 from the GitHub release asset digest); for Fabric, `fabric_api_url` and `fabric_api_sha512`; a `.gitignore` exception for a new loader's Baritone jar; and an entry in `.github/nodes.json` with the node's expected capabilities (the live and docker workflows both read it).

## Core invariants: do not break these

1. **HTTP, WebSocket, and MCP threads never touch game objects.** Game reads and writes go through `queue.submit(() -> ..., config.requestTimeoutMs)`. The queue drains at the end of each client tick under `tickBudgetMicros`, and results are serialized to JSON on the client thread. A full queue returns 503 and a missed deadline returns 504. Handlers set `X-MCDT-Tick` from the task result and return through `ctx.future(...)`. Copy `api/v1/PlayerLookHandler.java`.
2. **Errors are RFC 9457 problems** built with `http/Problems.java` (`throw Problems.invalidParameter(...)` etc.). Problem `type` strings are public API: add new constants, never rename existing ones.
3. **Every route declares an `McdtRole`** (`READ`, `ACT`, `REFLECT_WRITE`) when it is registered in `HttpServer`. `AccessFilter` enforces the role against the configured `permission`. It also blocks `ACT` and `REFLECT_WRITE` on multiplayer servers that are not in `allowedMultiplayerServers`.
4. **Baritone is optional.** Imports of `baritone.*` may appear only under `baritone/bridge/`. The JVM loads classes lazily, so the mod still runs without Baritone. In that case `UnavailableBaritoneService` makes the routes return 501 `baritone-not-installed`. Baritone is compile-only, only `suggests` in `fabric.mod.json`, and an `optional` dependency in `neoforge.mods.toml` (its NeoForge mod id is `baritoe`, sic).
5. **Reflection safety.** `ReflectionSecurity` holds the class denylist and redacts session credentials. The denylist covers Runtime, Process/ProcessBuilder, System, Thread, Unsafe, `java.io` file classes, any ClassLoader, and the `java.lang.reflect`, `java.lang.invoke`, `java.nio.file`, `java.nio.channels`, `java.net`, `java.security`, `jdk.internal`, and `sun.` packages. Treat both as security boundaries. Do not add an eval or arbitrary-code endpoint (that is a stated non-goal).
6. **One mod jar per node.** The `core` and `server` classes and resources are merged into each node's jar. Their third-party libraries are jar-in-jar: `bundled(project(":server"))` is walked (`McdtBuild.bundledModules`) and every external module is added to Loom's `include` or MDG's `jarJar`. ASM, slf4j, and jetbrains annotations are excluded on purpose. A bundled ASM conflicts with Mixin's and crashes startup with a `LinkageError`, and NeoForge pins slf4j strictly. **On NeoForge**, Jackson, Javalin and the OpenAPI plugins are not nested but merged into the jar by `shadowJar` with Jackson relocated to `dev.advik.mcdt.shaded.jackson`: Minecraft 1.21.2+ ships Jackson 2.13 as a module in a parent layer, and a nested Jackson crashes NeoForge at startup (`reads more than one module named com.fasterxml.jackson.annotation`). Fabric loads the bundled Jackson fine; `tests/live/test_runtime.py` checks which Jackson the mod runs with.
7. **Mixins** must be listed in `mcdt.mixins.json`. `defaultRequire` is 1, so an injection that fails to apply crashes the game. Keep mixins few and small.
8. **Security defaults stay safe.** Loopback bind, a per-launch bearer token, no token in query strings, Origin and Host checks, and `read` as the default permission.

## Recipes

**Add a curated endpoint.** Do these steps in order:
1. Add a request/response DTO in `core/.../api/v1/dto/`.
2. Add a method to `core/.../game/GameClient`.
3. Implement it in `src/.../game/MinecraftGameClient` (the platform) so it compiles on every node, with `//? if` conditionals where Minecraft versions differ. If a node can't support it, gate it on a `Capability`: add one to `core/.../api/v1/Capability` if none fits, leave it out of that node's `capabilities()`, wrap the route with `requires(...)` in `HttpServer` (and `BatchHandler.capabilityFor` / `McpToolRegistry.requiredCapabilityForTool` if they expose it), and have the node's implementation throw `Problems.featureUnavailable`.
4. Implement it in `core/src/testFixtures/.../api/v1/MockGameClient`. Tests do not compile without this step.
5. Add a handler in `server/.../api/v1/` with a full `@OpenApi` annotation (path, tags, `BearerAuth`, problem responses).
6. Register the route and its `McdtRole` in `server/.../http/HttpServer`.
7. If the endpoint has a new failure mode, add a `Problems` constant and factory (`core/.../http/Problems`).
8. Optional: add an MCP tool in `core/.../mcp/McpToolRegistry`. That means a `ToolDefinition`, a dispatch `case`, **and** an entry in `requiredPermissionForTool` (unlisted tools default to `REFLECT_WRITE`). Tools that touch the game go through `queue.submit`, like handlers.
9. If the dashboard uses it, update `web/src/lib/api.ts` and `types.ts`.
10. Add a sample to the matching `http/*.http` file.
11. Add a test in `server/src/test/.../api/v1/` and a live check in `tests/live/`.

**Add a config setting.**
1. Add the field with Javadoc in `config/McdtConfig`, and a clamp in `normalize()` if needed.
2. Copy it in `ConfigUpdater`, but only if it applies live. Otherwise it is restart-only.
3. Add it to `client/config/McdtConfigScreen`.
4. Add `mcdt.config.<name>` and `mcdt.config.<name>.desc` to `assets/mcdt/lang/en_us.json`.

**Emit an event.** Call `EventHub.getInstance().publish(topic, payload)` from `client/McdtClient` (wired to Fabric callbacks in `platform/fabric/FabricEntrypoint` and NeoForge events in `platform/neoforge/NeoForgeMod`) or a mixin. Keep payloads small and do not block on the game thread.

## Testing

- **Mock tests are often false signals that something has gone right.** They verify HTTP route protocol shape and DTO serialization, but cannot verify real game rendering, texture extraction, OpenGL/WebGPU pipelines, block model geometry, or client thread safety. Never rely solely on mock unit tests. **Always do live testing against a running Minecraft client before declaring any feature or bug fix done.**
- Unit and integration tests run **without Minecraft**. They construct a real `HttpServer` with `MockGameClient` (and `MockBaritoneService` for Baritone tests), `config.preferredPort = 0`, `BearerToken.of("test-secret-token")`, and a `McdtEnvironment` rooted in a JUnit `@TempDir`, then call it with `java.net.http.HttpClient`. Use `api/v1/CuratedActionEndpointsTest` as the template.
- Close the server, queue, and log writer in `@AfterEach`.
- Game-specific code in `MinecraftGameClient`, mixins, and `bridge/` is not unit-tested. Check it live with the Python suite in `tests/live/` (uv + pytest + httpx). It finds the instance via `~/.mcdt/instances/<pid>.json` or `run/config/mcdt/instance.json`:
  1. `./gradlew :1.21.4-fabric:runClient` (or another node).
  2. On the title screen: `uv run --project tests/live pytest tests/live`. In a world: add `--state world`. `--baritone installed|absent` asserts which Baritone mode is expected; `--shutdown` finishes with the quit flow.
- CI runs the same suite on real headless clients (`.github/workflows/live.yml` -> `scripts/ci/live-client.sh`): title screen, then a generated superflat world (world and Baritone checks), then the quit flow. Every node runs once with Baritone and once without; each matrix row pins the capabilities the node must report (`MCDT_EXPECT_CAPABILITIES`, from `.github/nodes.json`). `docker.yml` runs the same script with `LAUNCH=docker` against each node's image. Never run Docker locally; use CI.
- Before you call a change done, run `./gradlew test`, plus `bun run check` in `web/` if you touched the WebUI, followed by live verification on the running client.

## Conventions

- Java and Gradle KTS: **tabs**. `web/`: 2 spaces, no semicolons in TS. LF line endings everywhere except `*.bat`.
- Comments are sparse: short Javadoc on public types and non-obvious fields. Match the code around you.
- Commits: Conventional Commits (`feat:`, `fix(build):`, `ci:`, `feat(web):`, `docs:`). The default branch is `master`. Work on a branch and merge with a PR.
- Don't reformat unrelated code, and don't bump pinned versions or checksums unless the task asks for it.

## Gotchas

- The README says `mcdt.json` in one place, but the config file is `config/mcdt/config.json` in the game directory. The bearer token is in `config/mcdt/token`, and discovery files are in `~/.mcdt/instances/`.
- `run/`, `build/`, `web/node_modules`, `web/dist`, `.agents/`, and `skills-lock.json` are gitignored. A local daisyUI agent skill lives in `.agents/skills/daisyui/`. Use it for WebUI styling work if it is present.
- `NameService.initialize` runs inside the `HttpServer` constructor. On Fabric in production (intermediary namespace) it synchronously downloads the Mojang client mappings from piston-meta on first launch, caches them in `config/mcdt/mappings/`, and falls back to intermediary names if the download fails. Dev runs, NeoForge (always Mojang names) and tests skip it. Curated endpoints, events, and `/v1/packets` use `NameService.getInstance()` only to show or accept Mojang class names, and they fall back to runtime names. Never use class *names* for security decisions: `ReflectionSecurity` identifies the session `User` class by type, because its name is intermediary in production.
- The README lists `GET /v1/baritone/jobs` (a list endpoint), but only `/v1/baritone/jobs/{id}` is registered. This is one more reason to check `HttpServer` before you trust the docs.
- The license is CC0-1.0 (`LICENSE`, `fabric.mod.json`, `neoforge.mods.toml`).
- Compiling a node does not check mixin targets. When you add a node, compare every `@Accessor`/`@Invoker`/`@Inject` target against that version's named Minecraft jar (javap), or the game crashes at startup (`defaultRequire` is 1).
