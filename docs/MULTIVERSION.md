# Plan: Multi-version, multi-loader MCDT

## Context

MCDT currently ships one jar: **Fabric, Minecraft 1.21.4, Java 21**. The goal is to support as many players as possible, especially the "iconic" versions people actually play. Choices made during planning:

- **Java 8 path: both, staged.** Legacy builds first run on modern-Java forks (Cleanroom, lwjgl3ify, Legacy Fabric on 17+). A Java 8 transport comes later, so plain Forge 1.8.9 and 1.12.2 work too.
- **Legacy loaders: Forge + Legacy Fabric.**

What makes this feasible:

- About 22k lines of Java, of which only about 5.8k touch Minecraft or Fabric.
- Almost all of that is behind the `GameClient` interface (70 methods), plus 19 small mixins and accessors.
- Fabric API use is tiny: tick and lifecycle events, plus 5 `FabricLoader` call sites.
- The source is already Java 17-compatible: 59 records, 63 `instanceof` patterns, 4 text blocks, and no switch patterns, sealed types, or virtual threads.

The main obstacle: **Javalin 7 / Jetty 12 require Java 17+**, 89 files import Javalin, and 1.7.10, 1.8.9, 1.12.2, and Forge 1.16.5 run on Java 8 by default.

## Target matrix

| MC | Why it's iconic | Loaders | Runtime Java | Runtime names | Phase |
|---|---|---|---|---|---|
| **26.x (latest)** | Current release, first unobfuscated line | Fabric, NeoForge | 25 | Mojmap (unobfuscated) | 3 |
| **1.21.11** | Last obfuscated release, with many Fabric packs | Fabric, NeoForge | 21 | intermediary / Mojmap | 3 |
| **1.21.4** | Current target | Fabric, NeoForge | 21 | intermediary / Mojmap | 1–2 |
| **1.21.1** | Today's NeoForge modpack standard; #1 Fabric modpack version | Fabric, NeoForge | 21 | intermediary / Mojmap | 2 |
| **1.20.1** | Modern modding standard ("the new 1.12.2") | Forge, Fabric (NeoForge 47.1 loads Forge mods) | 17 | SRG / intermediary | 3 |
| **1.19.2** | Popular Forge and Fabric version | Forge, Fabric | 17 | SRG / intermediary | 3 |
| **1.18.2** | Popular Forge version | Forge, Fabric | 17 | SRG / intermediary | 3 |
| **1.16.5** | Stable Nether Update version | Fabric (Java 17+), Forge (Java 8) | 8 / 17 | intermediary / SRG | 3 (Fabric), 6 (Forge) |
| **1.16.1** | Speedrunning (MCSR) | Fabric | 17+ (what MCSR uses) | intermediary | 3 |
| **1.12.2** | Modding golden age | Forge: Cleanroom on Java 21 first, plain Forge on Java 8 later; Legacy Fabric | 21 → 8 | SRG→MCP / legacy intermediary | 4, 6 |
| **1.8.9** | PvP standard (Hypixel) | Legacy Fabric first; plain Forge on Java 8 later | 17 → 8 | SRG→MCP / legacy intermediary | 4, 6 |
| **1.7.10** | Classic modding (GTNH) | Forge: lwjgl3ify on Java 17–21 first, plain on Java 8 later | 17 → 8 | SRG→MCP | 4, 6 |

**Out of scope for now:** b1.7.3, 1.5.2/1.6.4, and 1.2.5 (the Tekkit era). They could be revisited later through Ornithe or Babric.

**Caveats to document:**
- Speedrun.com/MCSR only allows whitelisted mods, so MCDT on 1.16.1 is for practice and tooling, not for verified runs.
- On 1.8.9, input injection on servers like Hypixel can get users banned. The existing rule that blocks `ACT` on multiplayer by default (invariant 3) must stay on, and the README should say why.

## Architecture

### Gradle layout

```
core/            Not tied to Minecraft or a loader. Compiled with --release 17 (downgraded to Java 8 in Phase 5).
                 Contains: config, exec, events, security logic, mcp, discovery, json, Problems,
                 api DTOs, the GameClient interface, a Capability enum, and the reflection engine
                 (PathEvaluator, HandleTable, ReflectionSecurity core).
server-javalin/  The current HttpServer, handlers, AccessFilter adapter, WS/SSE, and docs routes (Java 17+).
server-netty/    Phase 5: a Java 8 HTTP/WS/SSE transport on shaded, relocated Netty 4.1.
platform/        Code tied to Minecraft, multiplexed by Stonecutter. Two source "eras":
  modern/          Mojmap source names, 1.16.1 → 26.x
  legacy/          MCP source names, 1.7.10 / 1.8.9 / 1.12.2
                 Each era contains: GameClient impl, mixins, serializers, viewer, input,
                 raycast, PacketResolver, mapping provider, and loader entrypoints.
web/             Unchanged; reads capabilities and hides unsupported panels.
```

### Build tooling

- **Stonecutter** handles the version × loader grid. Loaders are *branches* (`fabric`, `neoforge`, `forge`, `legacyfabric`, `legacyforge`); versions are *nodes*. Small differences use `//? if` comments. Bigger ones get swapped compat classes such as `ItemCodec`, `ToastAccess`, `RenderCompat`, and `InputBackend`.
- **Fabric Loom** builds Fabric targets: `fabric-loom-remap` up to 1.21.11, plain `net.fabricmc.fabric-loom` for 26.x.
- **ModDevGradle** builds NeoForge targets, and **MDG Legacy** builds Forge 1.17–1.20.1.
- **Unimined** builds the legacy era (Forge 1.7.10/1.8.9/1.12.2, Legacy Fabric) and Forge 1.16.5. The key reason: it can give *both* legacy Forge and Legacy Fabric the same **MCP dev mappings**, so one legacy source tree compiles for both loaders. Fallback if it fails: RetroFuturaGradle for 1.7.10, and Essential's toolkit (architectury-loom fork + preprocessor) for 1.8.9/1.12.2.
- **JvmDowngrader** (Phase 5) turns `core` and `server-netty` into Java 8 bytecode with shaded API stubs.

### Seams to add in `core`

- **`PlatformHooks` SPI**: registers the tick-drain callback and the shutdown hook, and provides the game dir, mod version, loader id, MC version, Java version, a `MappingProvider`, and an optional config-screen registration. This replaces the direct `FabricLoader` / `ClientTickEvents` use in `client/MinecraftDevToolsClient.java`, `McdtCore.java`, and `NameService.java`.
- **`MappingProvider` SPI**, extracted from `reflection/NameService.java`. Implementations:
  - Mojmap-download: what exists today, for Fabric up to 1.21.11 and Forge 1.17–1.20.4.
  - Identity: NeoForge, Forge 1.20.6+, and 26.x.
  - SRG→MCP: legacy Forge. Uses the MCP stable CSVs, downloaded from the Forge maven and cached in `config/mcdt/mappings/` like the Mojang mappings.
  - Legacy-intermediary→MCP: Legacy Fabric.
- **Capabilities.** A `Capability` enum (e.g. `ITEM_COMPONENTS`, `OFFHAND`, `TOASTS`, `ADVANCEMENTS`, `BOSSBARS`, `SUBTITLES`, `BLOCK_VIEWER`, `TEXTURES`, `DECOMPILER`, `BARITONE`, `CHAT_SIGNING`) is reported in `/v1/health` and filters MCP `tools/list`. Routes for a missing capability return **501 with a new `feature-unavailable` problem type** (a new constant in `http/Problems.java`; existing ones are not renamed). This follows the pattern of `UnavailableBaritoneService`.
- **Session redaction stays type-based in every era.** `ReflectionSecurity` must identify the legacy `Session` class (MCP name) as well as the modern `User` class. Each era's platform registers its credential class by type, never by name.

### Version breakpoints and where they live

- **Items:** data components (1.20.5+) vs NBT (1.20.4 and older). `ItemSerializer` gets two impls. The DTO gets optional `components` / `nbt` fields.
- **Blocks:** the Flattening (1.13). Up to 1.12, numeric id + meta is mapped to the registry name plus an extra `meta` field. 1.7.10 has no block states.
- **Input:** LWJGL2 `Keyboard`/`Mouse`/`KeyBinding` (up to 1.12, also emulated by lwjgl3ify and Cleanroom) vs GLFW (1.13+). These become two `InputBackend` impls behind `KeyboardHandlerAccessor` / `MouseHandlerAccessor`.
- **Packets/connection:** chat signing (1.19+), the configuration phase (1.20.2+), and the packet registry rework (1.20.5). `network/PacketResolver.java` gets one impl per range.
- **Rendering:** fixed-function GL (up to 1.12), then blaze3d (1.15–1.21.4), then `GpuDevice`/`RenderPipeline` (1.21.5+), plus GUI rendering changes after that. This affects `game/viewer/ViewerWorld.java`, the texture/atlas accessors, and `GuiAccessor`.
- **GUI classes:** for example, `ToastComponent` became `ToastManager` in 1.21.2.
- **Mixins:** an `IMixinConfigPlugin` enables mixins per version. The legacy era gets its own mixin JSON. Mixin sources: Cleanroom has it built in; MixinBooter for plain Forge 1.12.2; UniMixins for 1.7.10; Legacy Fabric has it built in; a bundled Mixin tweaker for plain Forge 1.8.9 (the same approach Essential and OneConfig use).

## Working conventions

- Work on a branch (e.g. `feat/multiversion`), never on `master`.
- **Commit as you go**: one commit per logical step, so each phase lands as several small commits.
- Commits use the repo's git user (Advik) **only**, with no `Co-Authored-By` or other AI trailer.
- Messages are short Conventional Commits subjects, e.g. `refactor: split core module`, `feat(neoforge): add entrypoint`.

## Phases

### Phase 0: Spikes (throwaway, about 1 week)
1. Build `core` with `--release 17` and confirm it compiles and the tests pass.
   **Done (2026-11-04): passed.** I switched every `options.release` in `build.gradle.kts` from 21 to 17 temporarily. Main and test sources compiled with no errors (class major version 61). 308/309 tests passed. The one failure, `McpShimTest.testPythonShimStdioRelay`, happens because `python` isn't on PATH on the dev machine, so it's unrelated to the release level. The build change was reverted; Phase 1 sets `core` to 17 for real.
2. Launch Legacy Fabric 1.8.9 on Java 17 and on Java 21. The result decides whether 1.8.9 ships in Phase 4 or waits for Phase 6.
3. Use Unimined to build a hello-world mod for Forge 1.8.9, Forge 1.12.2, and Legacy Fabric 1.8.9 from **one** MCP-named source set, inside Stonecutter.
4. On NeoForge 1.21.1, jar-in-jar Javalin, Jetty, and Jackson, and confirm they load on the JPMS module layer.
5. Run JvmDowngrader on the current core jar, then run the DTO, MCP, and EventRingBuffer tests on JDK 8. In particular, check how Jackson serializes downgraded records.

The spike code lives in `spikes/` and its CI in `.github/workflows/spikes.yml`. Launch tests run headless on GitHub Actions: portablemc 5.0.6, Xvfb, Mesa.

#### Phase 0 results (2026-11-04): all spikes passed

| # | Spike | Result |
|---|---|---|
| 1 | Build at `--release 17` | Passed (see above). |
| 2 | Legacy Fabric on modern Java | **1.8.9 and 1.12.2 run on Java 8, 17 and 21**: the mixin applied, and the in-process HTTP server answered. |
| 3 | One MCP source tree with Unimined | Passed. A single `spikes/legacy/mcp` tree built Forge 1.8.9 and 1.12.2 (searge + MCP stable 22 / 39, MixinBooter 9.3) and Legacy Fabric 1.8.9 and 1.12.2 (legacy intermediary in production, MCP names in dev). All four passed in-game. |
| 4 | NeoForge 1.21.1 jar-in-jar | Passed. The same library set as the Fabric jar (about 45 nested jars) loads; Javalin is the named JPMS module `io.javalin`; HTTP and WebSocket both work. |
| 5 | JvmDowngrader to Java 8 | Passed after one fix: 33/33 real core tests (config, exec, events, discovery, DTO JSON) pass as Java 8 bytecode on a Java 8 JVM. |
| – | Plain Forge 1.12.2 on Java 17 (control) | Fails as expected: LaunchWrapper casts `AppClassLoader` to `URLClassLoader`. Plain Forge stays Java 8 only (Phase 6). |

What changes in the plan:
- **Legacy Fabric 1.8.9 and 1.12.2 ship in Phase 4 on the Java 17 core.** They also run on Java 8, so the Java 8 transport in Phase 5 benefits them too.
- **Unimined is confirmed for the legacy era**, with no fallback needed. It also has a `cleanroom {}` patcher, so Cleanroom 1.12.2 needs no extra tooling. MCP names still differ between 1.8.9 and 1.12.2 (for example `Minecraft.mcDataDir` became `gameDir`), so the legacy era needs Stonecutter conditionals too.
- **NeoForge:** NeoForge pins slf4j `strictly 2.0.9`, so slf4j (and ASM) must be excluded from the library dependencies. That's fine, because they're never bundled. MDG's `jarJar` is not transitive, so every resolved artifact is added explicitly, the same way the Fabric `bundled` configuration works.
- **Downgraded records:** Jackson (Java 8 bytecode) cannot see records without `java.lang.Record`, so it serialized them as `{}` and couldn't construct them. `dev.advik.mcdt.json.RecordCompatIntrospector` fixes that. It's registered only when `java.lang.Record` is missing; JvmDowngrader keeps the component metadata that `Class.isRecord()` / `getRecordComponents()` read. One cosmetic difference remains: a downgraded record's `toString()` uses the binary name (`ChatDto$SendResponse[...]`).
- **Headless CI notes:** `--mc-dir` is a `start` option in portablemc 5. LWJGL 2 needs `xrandr` (`x11-xserver-utils`).
- **Unrelated bug found while planning Baritone tests:** the Baritone MCP tools ran off the client thread. Fixed in `fix(mcp): run baritone tools on client thread`.

### Phase 1: Module split + seams (still Fabric 1.21.4 only, no behavior change)
- Split the code into `core` / `server-javalin` / `platform`. Move `GameClient`, the DTOs, and everything else not tied to Minecraft into `core`.
- Add `PlatformHooks`, `MappingProvider`, `Capability`, and `feature-unavailable`. Gate the WebUI (`web/src/lib/api.ts`, `types.ts`) and the MCP tool list on capabilities.
- New code stays independent of the transport: handler logic gets its inputs as DTOs and returns `TaskResult`. This makes Phase 5 cheaper.
- Update `AGENTS.md` (layout and recipes) and `build.gradle.kts` (the `bundled` config moves to the server module; keep the ASM/slf4j exclusions).

#### Phase 1 progress (2026-11-04)

**Done:**
- **Module split.** The projects are `core/` (103 classes, `--release 17`, no Minecraft, loader or Javalin types), `server/` (67 classes: Javalin, handlers, `McdtCore`) and the root Fabric 1.21.4 platform (34 classes). The mod jar merges all three; the only differences from the pre-split jar are the intended ones, the 43 nested library jars are identical, and `openapi.json` is unchanged. Tests split 87 core, 201 server, 22 platform (310, the same as before). `MockGameClient` / `MockBaritoneService` are `core` test fixtures.
- **Seams.** Each one keeps Minecraft and loader types out of core. Platform implementations are found with `ServiceLoader`, which works regardless of init order:
  - `MappingProvider`: runtime names. Replaces Fabric's `MappingResolver` in `NameService`.
  - `GameTypeAdapter`: Minecraft types in the reflection serializer and `TypeConverter`.
  - `SessionClassProvider`: the session class, for redaction by type.
  - `ModResourceSource`: mod jars for the decompiler.
  - `EventSink` plus an injected tick source in `EventHub`.
  - `GameClient.packetType()` for packet resolution.
- **Live CI** (`.github/workflows/live.yml`, `scripts/ci/live-client.sh`) runs a real headless Fabric 1.21.4 client with and without Baritone:
  - the Python suite on the title screen;
  - a superflat world from the dedicated server, loaded through `POST /v1/worlds/{id}/load`, with the world and Baritone checks;
  - the two-step quit flow.
- **Python live suite** (`tests/live/`, uv + pytest + httpx + websockets): a port of the old PowerShell smoke test plus Baritone, world, and events-stream tests.

**Bugs the live suite found and fixed** (all pre-existing on `master`):
- **Remapped decompile broken in production.** `/v1/classes/{name}/source?remap=true` failed with an NPE inside Vineflower's lambda processor whenever its identifier renamer was on. Fix: class files are now remapped with ASM before decompiling (`MojangRemapper`), and the isolated renamer was removed.
- **Baritone settings returned 500.** `GET /v1/baritone/settings` failed because some setting values are `Item`/`Block` lists. Fix: values are now converted to registry ids, enum names, and so on.
- **Baritone off the client thread (MCP tools).** The Baritone MCP tools (`baritone_goto`, `_mine`, `_follow`, `_cancel`) called Baritone and Minecraft registries off the client thread (invariant 1). Fix: they now run through the task queue, with a regression test.
- **Baritone off the client thread (settings routes).** `GET`/`PATCH /v1/baritone/settings` also ran on Jetty threads. Fix: queued.
- **Packets built off the client thread.** `/v1/packets` resolved and constructed packet classes on Jetty threads. Fix: that work now runs in the queued task.
- **Baritone jobs wrongly marked cancelled.** Two causes:
  - Baritone delivers path events one tick late, so the `CANCELED` from replacing the previous goal ended the new job. Fix: such stale events are ignored.
  - Baritone reports reaching a coordinate goal as `CANCELED` (the process gives up control) rather than `AT_GOAL`. Fix: such a job now completes when the player is in the goal.

**Left for Phase 1:**
- `Capability` + `feature-unavailable`, with WebUI and MCP gating. This is only needed once a platform lacks a feature, so it lands with the first such node in Phase 2/3.
- `PlatformHooks` turned out unnecessary. The platform entrypoint already builds `McdtEnvironment` and drives the tick; the loader-specific lookups went behind the SPIs above.

### Phase 2: Stonecutter + NeoForge (1.21.4, then 1.21.1)
- Stonecutter with branches `fabric` and `neoforge`, and nodes 1.21.1 and 1.21.4.
- NeoForge entrypoint: `ClientTickEvent.Post` drains the queue, `IConfigScreenFactory` + YACL for config, jarJar for the bundled libs, and the identity `MappingProvider`.
- Baritone: pin the API jar and SHA per node and loader in each node's `gradle.properties`. The bridge stays under `baritone/bridge/`. Where Baritone's API changed between releases, add a Stonecutter conditional or a per-range bridge class. Nodes with no Baritone release (expected: 1.7.10, 1.8.9) ship without the bridge and report the `BARITONE` capability as off.
- **Baritone live coverage** (in the Python live suite, `tests/live/`):
  - **With Baritone:** `GET /v1/baritone/status`; `POST /v1/baritone/goto` a few blocks away, then poll `GET /v1/baritone/jobs/{id}` until it reaches a terminal state; `GET /v1/baritone/path`; `POST /v1/baritone/cancel`; read `GET /v1/baritone/settings` and round-trip a `PATCH`; `POST /v1/baritone/command`; a short `mine` / `follow` / `explore` / `farm` / `build` start followed by cancel; and the `baritone*` MCP tools through `/mcp`.
  - **Without Baritone:** the same routes return 501 `baritone-not-installed`, and the capability is off.
  - **Docker:** each node runs twice, `MC_BARITONE=true` and `MC_BARITONE=false`. `docker/entrypoint.sh` and the `Dockerfile` copy the Baritone jar that matches the node's MC version and loader (today they hardcode `baritone-api-fabric-*`).
- Versioning: `mcdt-<modver>+<mc>-<loader>.jar`, replacing `1.1.0-mc_1.21.4`.
- **Live-test matrix starts here:**
  - Parameterize `Dockerfile` ARGs as `MC_VERSION` + `LOADER` (portablemc launches fabric, forge, and neoforge).
  - Add a CI job per node that boots the headless client and runs the Python live suite (`tests/live/`) through `scripts/ci/live-client.sh`.
  - Every later phase adds its nodes to this matrix.

### Phase 3: Modern version spread (Mojmap era)
Order: **1.21.11 → 26.x latest → 1.20.1 → 1.19.2 → 1.18.2 → 1.16.5 Fabric → 1.16.1 Fabric**.
- **26.x:** Java 25, no remapping. Use `implementation` instead of `modImplementation`, Mixin `JAVA_25`, and the identity mappings.
- **1.20.1 / 1.19.2 / 1.18.2:** add the Forge branch through MDG Legacy, compile with Java 17, use the NBT item serializer, and the SRG→Mojmap provider (MCPConfig + Mojang mappings).
- **1.16.x Fabric:** these nodes require a Java 17+ runtime until Phase 6. Show a clear startup message (no crash) when MCDT runs on older Java.
- **Rendering:** the viewer and texture features ship only where the `RenderCompat` impl exists. Everywhere else the capability is off.
- For each node: `chiseledBuild`, unit tests, and a Docker live smoke test.

### Phase 4: Legacy era on modern Java
- Create the `platform/legacy` source era (MCP names) with Unimined targets:
  - **Forge 1.12.2 via Cleanroom** (Java 21)
  - **Forge 1.7.10 via lwjgl3ify/GTNH** (Java 17–21)
  - **Legacy Fabric 1.8.9 and 1.12.2** (Java 17+, if the Phase 0 spike passed)
- Start with a legacy feature core: player, world/block reads, inventory, chat, input, screens, events, reflection, and packets. Viewer, textures, toasts, advancements on 1.7.10/1.8.9, offhand, and bossbars report their capability as off.
- Startup guard: on Java below 17, log an error, show an in-game message ("MCDT needs Java 17+ on this version"), and disable MCDT without crashing.
- Live testing: portablemc may not support Cleanroom or lwjgl3ify, so start with Prism Launcher instances and a documented manual checklist. Automate later if possible.

### Phase 5: Transport abstraction + Java 8 server
- Add `RouteSpec(method, path, role, operation)` and `McdtExchange` (body, path/query params, headers, response future) to `core`, plus `McdtSocketHandler` for WS and an SSE interface.
- Move the `AccessFilter` logic (bearer, Host, Origin, rate limit, role, multiplayer allowlist) into `core` and keep thin per-transport adapters.
- Convert the handlers mechanically, using `api/v1/PlayerLookHandler.java` as the model. Javalin becomes an adapter.
- OpenAPI: keep the `@OpenApi` annotations in `server-javalin`, generate `openapi.json` at build time, and ship it as a resource for the Netty transport.
- `server-netty`: Netty 4.1, **shaded and relocated** (`dev.advik.mcdt.shaded.netty`) so it never touches Minecraft's own old Netty. Covers HTTP/1.1, WS, SSE, and static files for the WebUI and Scalar.
- Run JvmDowngrader on `core` + `server-netty`, targeting Java 8. Make Jackson treat the downgraded records correctly, either by annotating DTO components with `@JsonProperty` or with a custom accessor-naming strategy.
- Transport selection: Java 17+ uses Javalin, Java 8 uses Netty.
- Make the existing HTTP, WS, MCP, and security integration tests **parameterized over both transports**, and add a CI job that runs them on JDK 8 against the downgraded jars.
- Turn the decompiler (Vineflower) capability off on runtimes it doesn't support.

### Phase 6: Java 8 targets
Add these, using the legacy era code from Phase 4 plus the Java 8 transport:
- **Plain Forge 1.8.9**: LaunchWrapper, with Mixin through a bundled tweaker; test against Essential and OneConfig for conflicts.
- **Plain Forge 1.12.2** with MixinBooter.
- **Plain Forge 1.7.10** with UniMixins.

Also add **Forge 1.16.5** (modern era, Java 8, downgraded node) and let the **1.16.x Fabric** nodes run on Java 8. Remove the Java 17 startup guard wherever the Java 8 transport is bundled.

### Phase 7: Parity, docs, release
- Legacy rendering: a fixed-function GL `RenderCompat` for the viewer and texture extraction on up to 1.12.
- Support tiers:
  - **Tier 1**: latest 26.x, 1.21.1, 1.20.1, 1.12.2, 1.8.9. Full features, live-tested on every release.
  - **Tier 2**: everything else. Best effort, smoke-tested.
- Docs: a compatibility matrix in README; updates to `MCDT.md` and `AGENTS.md` (the "Add a curated endpoint" recipe now says: implement in each era's `GameClient`, or declare a capability and return `feature-unavailable`); `http/*.http` samples.
- Release: GitHub Actions builds all nodes and publishes to Modrinth and CurseForge (e.g. mc-publish) with correct loader and version tags.

## Critical files

- `build.gradle.kts`, `settings.gradle.kts` (+ new `stonecutter.gradle.kts`), `gradle.properties` (per-node versions move to `versions/<node>/gradle.properties`)
- `src/main/java/dev/advik/mcdt/McdtCore.java`, `client/MinecraftDevToolsClient.java`: replace direct Fabric use with `PlatformHooks`
- `game/GameClient.java`: becomes the per-era impls; `game/MinecraftGameClient.java` splits per era with compat classes
- `reflection/NameService.java`: becomes the `MappingProvider` SPI; `reflection/ReflectionSecurity.java`: credential class registered per era
- `http/HttpServer.java`, `security/AccessFilter.java`, all `api/v1/*Handler.java`: Phase 5 transport abstraction
- `http/Problems.java`: new `feature-unavailable` constant
- `src/main/resources/mcdt.mixins.json` + a new mixin config plugin and a legacy mixin JSON
- `game/serializers/*`, `game/viewer/ViewerWorld.java`, `network/PacketResolver.java`, `mixin/*Accessor.java`: compat splits
- `web/src/lib/api.ts`, `types.ts`: capability gating
- `Dockerfile`, `docker/entrypoint.sh`, `.github/workflows/build.yml`: matrix
- `AGENTS.md`, `README.md`, `MCDT.md`

## Verification (every phase)

1. `./gradlew test`: the unit and integration suites in `core` / `server-*` using `MockGameClient`. From Phase 5 on, they run against both transports, plus a JDK 8 job on the downgraded jars.
2. `./gradlew chiseledBuild`: every Stonecutter node compiles.
3. `cd web && bun run check` if the WebUI changed.
4. **Live, for every node added in the phase:** a Docker headless client with `MC_VERSION`/`LOADER`, then the Python live suite in `tests/live/` (including `--shutdown`). For Cleanroom, lwjgl3ify, and plain legacy Forge, use a Prism Launcher instance and the manual checklist: open a world, hit `/v1/health` (correct capabilities), read player/world/inventory, send chat, inject input, use reflection to read the session class (it must be redacted), and connect MCP from `scripts/mcdt-mcp.py`.
5. Security regression on each era: Host/Origin checks, no token in the query string, `ACT` blocked on a non-allowlisted multiplayer server.
6. **Baritone, on every node that has a Baritone release:** run the live Baritone section with Baritone installed *and* without it (see Phase 2). Also keep `BaritoneIntegrationTest` / `BaritoneNotInstalledTest` (`MockBaritoneService`) green in the unit suite, and run them against both transports from Phase 5 on.

## Key risks

- **Maintenance multiplier:** about 20 jars. Mitigated by support tiers, era-level code sharing, and CI live-smoke tests.
- **Mixin conflicts on plain Forge 1.8.9:** there's no standard Mixin there. Mitigate by testing against the popular PvP mods and Essential/OneConfig.
- **NeoForge JPMS issues** with Jetty/Jackson: tested early in Phase 0.
- **JvmDowngrader gaps** (missing API stubs, record semantics): tested in Phase 0 and by the JDK 8 CI job.
- **Unimined maturity:** fallbacks are RetroFuturaGradle and the Essential toolkit.

## Research references

- Fabric porting to 26.1 (unobfuscated, Java 25, no intermediary): https://docs.fabricmc.net/develop/porting/ , https://fabricmc.net/2025/12/05/12111.html
- Javalin 7 / Jetty 12 require Java 17: https://jetty.org/docs/jetty/12/
- Cleanroom (1.12.2 on Java 21): https://github.com/CleanroomMC/Cleanroom
- lwjgl3ify (1.7.10 on Java 17–21): https://github.com/GTNewHorizons/lwjgl3ify
- Legacy Fabric (1.3–1.14): https://legacyfabric.net/
- Unimined: https://github.com/unimined/unimined ; JvmDowngrader: https://github.com/unimined/JvmDowngrader
- Essential multi-version toolkit (proof that 1.8.9 Forge through modern versions works): https://github.com/EssentialGG/essential-gradle-toolkit
- UniMixins: https://github.com/LegacyModdingMC/UniMixins
- Fabric modpack counts by version (1.21.1 top): https://www.modpackindex.com/stats/loader/fabric
- Forge 1.16.5 is Java 8 (Java 16+ only with unofficial flags): https://forums.minecraftforge.net/announcement/15-java-16-and-you-how-to-avoid-crashes/
