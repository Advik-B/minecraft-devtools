# AGENTS.md

Guide for coding agents working in this repo. Read it before you change code.

## What this is

MCDT (Minecraft DevTools) is a **client-only Fabric mod**. Mod id: `mcdt`. Target: Minecraft 1.21.4, Java 21, Mojang mappings with a Parchment layer. The mod runs a Javalin 7 / Jetty 12 server inside the game on `127.0.0.1:25580` (it falls back to a random port if that one is taken). The server exposes the live client over REST, WebSocket, SSE, and an MCP server at `/mcp`. A Svelte 5 WebUI (in `web/`) is served at `/`.

What each document is for:

- `README.md`: the user-facing feature list.
- `MCDT.md`: the design doc and roadmap. **Parts of it are not built yet**, for example `/v1/view/blocks`, `/v1/hud`, `/v1/batch`, `/v1/worlds`, and `/v1/servers`.
- `src/main/java/dev/advik/mcdt/http/HttpServer.java`: **the source of truth for which routes exist**. Check it before you trust either doc.

## Layout

```
src/main/java/dev/advik/mcdt/
  Mcdt.java, McdtCore.java  mod id + logger; composition root (config, token, queue, server, discovery)
  client/                   Fabric entrypoint (tick hook drains the queue), YACL config screen + Mod Menu
  api/v1/                   one Handler class per route group, annotated with @OpenApi
  api/v1/dto/               request/response records (also drive the OpenAPI schema)
  game/                     GameClient interface (the seam tests mock), MinecraftGameClient (real impl), serializers
  exec/                     TaskQueue, GameTask, TickClock: bounded queue drained on the client thread
  http/                     HttpServer (routes), Problem/Problems (RFC 9457), WebSocket handlers, request log
  security/                 AccessFilter (bearer, Host, Origin, rate limit, role + multiplayer checks), confirm tokens
  reflection/               HandleTable, NameService (Mojang mappings), PathEvaluator, ReflectionSecurity
  events/                   EventHub + lock-free ring buffer + dispatcher for /v1/events
  mcp/                      JSON-RPC MCP server: McpHandler, McpToolRegistry, McpResourceRegistry
  baritone/                 BaritoneService interface, UnavailableBaritoneService; bridge/ is the only Baritone-aware code
  mixin/                    mixins + accessors, registered in src/main/resources/mcdt.mixins.json
  config/                   McdtConfig (config/mcdt/config.json), ConfigStore, ConfigUpdater
  docs/                     Scalar / WebUI static routes
src/test/java/...           JUnit 5, mirrors main packages
web/                        Svelte 5 + Vite + Tailwind 4 + daisyUI 5 dashboard (Bun)
scripts/                    MCP stdio shim (mcdt-mcp.py / .ps1), live-client test runner
http/                       .http request samples per area
libs/                       pinned Baritone API jar (committed via a .gitignore exception)
```

## Build, run, test

- `./gradlew build` (`gradlew.bat` on Windows). **Bun must be on PATH**: `installWeb` → `buildWeb` → `syncWebAssets` run `bun install --frozen-lockfile` and `bun run build`, and the output is packed into the jar.
- `./gradlew test` runs JUnit 5 and then the JaCoCo report. To run one class: `./gradlew test --tests 'dev.advik.mcdt.mcp.McpToolsTest'`.
- `./gradlew runClient` starts a dev client in `run/`. YACL and Mod Menu are on the dev runtime (`modLocalRuntime`). Baritone is not.
- A cold Gradle cache needs network access *at configure time*. `ensureBaritoneJar()` in `build.gradle.kts` verifies the Baritone jar against the SHA-256 pinned in `gradle.properties` and downloads it again if the check fails. Scalar is downloaded the same way, pinned by version and SHA.
- Code compiles with `--release 21`. CI (`.github/workflows/build.yml`) runs `./gradlew build` on ubuntu with JDK 25 (Microsoft) and Bun. The Gradle configuration cache is on.
- `runClient` serves the dashboard straight from `web/dist` (Gradle passes `-Dmcdt.webDist`), so `cd web && bun run build` followed by a browser reload picks up UI changes without restarting the game.
- WebUI dev: run `cd web && bun run dev` (Vite on :5173). It proxies `/v1`, `/mcp`, `/docs`, and `/openapi.json` to :25580. Add `http://localhost:5173` to `originAllowlist` in the config, or the API rejects the Origin. Type-check with `bun run check`.
- Versions of Minecraft, Loader, Fabric API, Javalin, Jackson, YACL, Mod Menu, and Baritone all live in `gradle.properties`.

## Core invariants: do not break these

1. **HTTP, WebSocket, and MCP threads never touch game objects.** Game reads and writes go through `queue.submit(() -> ..., config.requestTimeoutMs)`. The queue drains at the end of each client tick under `tickBudgetMicros`, and results are serialized to JSON on the client thread. A full queue returns 503 and a missed deadline returns 504. Handlers set `X-MCDT-Tick` from the task result and return through `ctx.future(...)`. Copy `api/v1/PlayerLookHandler.java`.
2. **Errors are RFC 9457 problems** built with `http/Problems.java` (`throw Problems.invalidParameter(...)` etc.). Problem `type` strings are public API: add new constants, never rename existing ones.
3. **Every route declares an `McdtRole`** (`READ`, `ACT`, `REFLECT_WRITE`) when it is registered in `HttpServer`. `AccessFilter` enforces the role against the configured `permission`. It also blocks `ACT` and `REFLECT_WRITE` on multiplayer servers that are not in `allowedMultiplayerServers`.
4. **Baritone is optional.** Imports of `baritone.*` may appear only under `baritone/bridge/`. The JVM loads classes lazily, so the mod still runs without Baritone. In that case `UnavailableBaritoneService` makes the routes return 501 `baritone-not-installed`. Baritone is `modCompileOnly` and only `suggests` in `fabric.mod.json`.
5. **Reflection safety.** `ReflectionSecurity` holds the class denylist and redacts session credentials. The denylist covers Runtime, Process/ProcessBuilder, System, Thread, Unsafe, `java.io` file classes, any ClassLoader, and the `java.lang.reflect`, `java.lang.invoke`, `java.nio.file`, `java.nio.channels`, `java.net`, `java.security`, `jdk.internal`, and `sun.` packages. Treat both as security boundaries. Do not add an eval or arbitrary-code endpoint (that is a stated non-goal).
6. **Jar-in-jar.** Libraries that ship inside the mod go in the custom `bundled` configuration, and `build.gradle.kts` adds them to Loom's `include`. ASM, slf4j, and jetbrains annotations are excluded on purpose. A bundled ASM conflicts with Mixin's and crashes startup with a `LinkageError`.
7. **Mixins** must be listed in `mcdt.mixins.json`. `defaultRequire` is 1, so an injection that fails to apply crashes the game. Keep mixins few and small.
8. **Security defaults stay safe.** Loopback bind, a per-launch bearer token, no token in query strings, Origin and Host checks, and `read` as the default permission.

## Recipes

**Add a curated endpoint.** Do these steps in order:
1. Add a request/response DTO in `api/v1/dto/`.
2. Add a method to `game/GameClient`.
3. Implement it in `game/MinecraftGameClient`.
4. Implement it in the test `api/v1/MockGameClient`. Tests do not compile without this step.
5. Add a handler in `api/v1/` with a full `@OpenApi` annotation (path, tags, `BearerAuth`, problem responses).
6. Register the route and its `McdtRole` in `HttpServer`.
7. If the endpoint has a new failure mode, add a `Problems` constant and factory.
8. Optional: add an MCP tool in `mcp/McpToolRegistry`. That means a `ToolDefinition`, a dispatch `case`, **and** an entry in `requiredPermissionForTool` (unlisted tools default to `REFLECT_WRITE`).
9. If the dashboard uses it, update `web/src/lib/api.ts` and `types.ts`.
10. Add a sample to the matching `http/*.http` file.
11. Add a test in `src/test/.../api/v1/`.

**Add a config setting.**
1. Add the field with Javadoc in `config/McdtConfig`, and a clamp in `normalize()` if needed.
2. Copy it in `ConfigUpdater`, but only if it applies live. Otherwise it is restart-only.
3. Add it to `client/config/McdtConfigScreen`.
4. Add `mcdt.config.<name>` and `mcdt.config.<name>.desc` to `assets/mcdt/lang/en_us.json`.

**Emit an event.** Call `EventHub.getInstance().publish(topic, payload)` from a Fabric callback (see `client/MinecraftDevToolsClient`) or a mixin. Keep payloads small and do not block on the game thread.

## Testing

- Unit and integration tests run **without Minecraft**. They construct a real `HttpServer` with `MockGameClient` (and `MockBaritoneService` for Baritone tests), `config.preferredPort = 0`, `BearerToken.of("test-secret-token")`, and a `McdtEnvironment` rooted in a JUnit `@TempDir`, then call it with `java.net.http.HttpClient`. Use `api/v1/CuratedActionEndpointsTest` as the template.
- Close the server, queue, and log writer in `@AfterEach`.
- Game-specific code in `MinecraftGameClient`, mixins, and `bridge/` is not unit-tested. Check it live:
  1. `./gradlew runClient`, then open a world.
  2. `pwsh scripts/test-live-client.ps1`. It finds the instance via `~/.mcdt/instances/<pid>.json` or `run/config/mcdt/instance.json`. `-ShutdownClient` also exercises the quit flow.
- Before you call a change done, run `./gradlew test`, plus `bun run check` in `web/` if you touched the WebUI.

## Conventions

- Java and Gradle KTS: **tabs**. `web/`: 2 spaces, no semicolons in TS. LF line endings everywhere except `*.bat`.
- Comments are sparse: short Javadoc on public types and non-obvious fields. Match the code around you.
- Commits: Conventional Commits (`feat:`, `fix(build):`, `ci:`, `feat(web):`, `docs:`). The default branch is `master`. Work on a branch and merge with a PR.
- Don't reformat unrelated code, and don't bump pinned versions or checksums unless the task asks for it.

## Gotchas

- The README says `mcdt.json` in one place, but the config file is `config/mcdt/config.json` in the game directory. The bearer token is in `config/mcdt/token`, and discovery files are in `~/.mcdt/instances/`.
- `run/`, `build/`, `web/node_modules`, `web/dist`, `.agents/`, and `skills-lock.json` are gitignored. A local daisyUI agent skill lives in `.agents/skills/daisyui/`. Use it for WebUI styling work if it is present.
- `NameService.initialize` runs inside the `HttpServer` constructor. In production (intermediary namespace) it synchronously downloads the Mojang client mappings from piston-meta on first launch, caches them in `config/mcdt/mappings/`, and falls back to intermediary names if the download fails. Dev runs and tests (named namespace) skip it. Curated endpoints never depend on the mappings.
- The README lists `GET /v1/baritone/jobs` (a list endpoint), but only `/v1/baritone/jobs/{id}` is registered. This is one more reason to check `HttpServer` before you trust the docs.
- The license is CC0-1.0 (`LICENSE`, `fabric.mod.json`).
