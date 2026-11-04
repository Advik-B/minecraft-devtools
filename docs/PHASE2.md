# Phase 2 plan: feature flags, Stonecutter, NeoForge, 1.21.1

## Context

Phases 0 and 1 are done on branch `multiversion`. `docs/MULTIVERSION.md` holds the overall plan and the progress log.

- The code is split into `core/` (no Minecraft types), `server/` (Javalin), and the root Fabric 1.21.4 platform.
- Loader and version specifics sit behind ServiceLoader SPIs:
  - `MappingProvider`
  - `GameTypeAdapter`
  - `SessionClassProvider`
  - `ModResourceSource`
- CI runs the Python live suite (`tests/live/`) on a real headless client, with and without Baritone.

Phase 2 makes MCDT multi-loader and multi-version for the first time: **Fabric + NeoForge on 1.21.4 and 1.21.1** (four nodes). It also includes:
- removing the old PowerShell live script;
- **feature flags (capabilities)**, so versions or loaders that lack a feature say so cleanly instead of failing.

Research behind the choices below:
- `vercim/ticks` and `MovTery/Quick-Chat` use exactly this setup: Stonecutter 0.9.8, nodes named `<mc>-<loader>`, each with `.buildscript = "build.<loader>.gradle.kts"`.
- Loader-only files are wrapped in `//? if fabric {` … `//?}`.
- Loader metadata lives in `src/<loader>/resources`.
- ModDevGradle 2.0.148 works for NeoForge (Phase 0 spike 4).
- Baritone ships `baritone-api-{fabric,neoforge}` for both versions: **1.21.1 → Baritone 1.11.3**, **1.21.4 → 1.13.1**.
- NeoForge versions: **21.4.161** and **21.1.265**.

Working rules:
- Commit as you go: author Advik only, short Conventional Commit messages, no AI trailer.
- CI only runs on GitHub Actions; no local Docker.
- Live tests are in Python, run with uv.

## Steps (each ends with a commit; push after each step and watch CI)

### 0. Housekeeping
- Add this plan as `docs/PHASE2.md`.
- Delete `scripts/test-live-client.ps1`, and remove its references in `README.md`, `AGENTS.md` and `docs/MULTIVERSION.md`. Point readers at `tests/live/` instead.
- Commit: `chore: remove powershell live script`.

### 1. Feature flags (capabilities), on the current Fabric 1.21.4 build
**Core** (`core/src/main/java/dev/advik/mcdt/`):
- `api/v1/Capability.java`: an enum with JSON-friendly ids. Initial set: `TEXTURES`, `BLOCK_VIEWER`, `WORLD_STREAM`, `SCREENSHOT`, `PACKETS`, `INPUT`, `ADVANCEMENTS`, `RECIPES`, `STATS`, `TOASTS`, `BOSSBARS`, `SUBTITLES`, `OFFHAND`, `ITEM_COMPONENTS`, `CHAT_SIGNING`, `DECOMPILER`, `BARITONE`, `CONFIG_SCREEN`.
- `game/GameClient`: add `Set<Capability> capabilities()`, which the platform declares. `MockGameClient` (test fixture) gets a settable `EnumSet`, defaulting to all.
- `http/Problems`: a new constant `FEATURE_UNAVAILABLE = "feature-unavailable"` (501) and a factory `featureUnavailable(Capability)`, whose detail names the capability and the platform. Existing types are unchanged: Baritone keeps `baritone-not-installed`, the decompiler keeps its current type.
- `api/v1/HealthResponse`: a new `capabilities` field (a sorted list of ids). This adds to the API and renames nothing.
- `mcp/McpToolRegistry`: each `ToolDefinition` can name a required capability. `tools/list` hides tools whose capability is off, and `tools/call` on them returns a tool error.

**Server:**
- `HttpServer`: a small `requires(Capability, Handler)` wrapper used when routes are registered, e.g. textures → `TEXTURES`, `/v1/view/blocks` and block models → `BLOCK_VIEWER`, `/v1/world/stream` → `WORLD_STREAM`.
- The effective set is the platform's set plus runtime facts: Baritone installed, decompiler host up, config enabled.

**Platform:** `MinecraftGameClient.capabilities()` returns every capability on 1.21.4.

**WebUI:**
- `web/src/lib/types.ts`: add `capabilities: string[]` to the health type.
- The tab definitions (`Header.svelte` / `App.svelte`): each tab can name a capability, and tabs whose capability is off are hidden. Mapping: Textures → `TEXTURES`, Viewer → `BLOCK_VIEWER`, Baritone → `BARITONE`.
- Run `bun run check`.

**Tests:**
- New server test `CapabilitiesTest`:
  - with the mock set missing `TEXTURES`, the textures route returns 501 `feature-unavailable`;
  - health lists the capabilities;
  - the matching MCP tool is hidden, and calling it errors.
- Live: `tests/live/test_capabilities.py` checks that health lists capabilities and, for each capability that is off, that a representative route returns 501 `feature-unavailable`. The expected set per node comes from `--capabilities` / `MCDT_EXPECT_CAPABILITIES`; when unset, it only checks consistency.

Commit: `feat: add capability flags`.

### 2. Stonecutter, with one node (1.21.4-fabric) and no behaviour change
- Stonecutter controls the root project. The `core` and `server` projects stay as they are.
- `settings.gradle.kts`: `id("dev.kikugie.stonecutter") version "0.9.8"`, `stonecutter { create(rootProject) { version("1.21.4-fabric", "1.21.4").buildscript = "build.fabric.gradle.kts"; vcsVersion = "1.21.4-fabric" } }`. Keep `include("core", "server")`.
- `stonecutter.gradle.kts` (the controller):
  - `stonecutter active file(".sc_active_version")`;
  - constants from the node suffix: `constants.match(current.project.substringAfterLast('-'), "fabric", "neoforge")`;
  - `collectJars`: copies every node's remapped jar to `build/libs/`, so workflows keep their paths;
  - `exportOpenApi` delegates to `:server:exportOpenApi`.
- `build.fabric.gradle.kts`: today's root `build.gradle.kts`, with per-node values taken from `versions/<node>/gradle.properties`:
  - `minecraft_version`, `fabric_api_version`, `parchment_version`, `baritone_version`/`sha256`, `yacl_version`, `modmenu_version`, Java level.
  - It keeps the bundled-libs graph walk, the core/server jar merge, and `ensureBaritoneJar` (now per node: `libs/baritone-api-<loader>-<ver>.jar`, with a `.gitignore` exception for each).
- Move `src/main/resources/fabric.mod.json` to `src/fabric/resources/`. Fill `minecraft`, `java` and the Baritone `suggests` from properties.
- Split `client/MinecraftDevToolsClient`:
  - a loader-neutral `client/McdtClient`, with the startup, tick, stop, join/disconnect, chat, chunk and entity handlers that exist today;
  - `platform/fabric/FabricEntrypoint`, which registers Fabric API events and calls `McdtClient`.
- Wrap these Fabric-only files in `//? if fabric {`: `platform/fabric/*`, `client/config/McdtModMenuApi`. Move their `META-INF/services` entries to `src/fabric/resources`. The shared adapters (`MinecraftTypeAdapter`, `MinecraftSessionClass`) keep their entries in `src/main/resources`.
- `McdtConfigScreen`: replace the `FabricLoader` use with loader-neutral data (the config dir from `McdtCore`/`McdtEnvironment`).
- Version in `gradle.properties`: `version=1.1.0`. Each node builds `mcdt-<version>+<mc>-<loader>.jar`. Update `.github/workflows/release.yml`'s version bump (tags become `v<version>`), and the jar path in `Dockerfile`, `build.yml` and `live.yml`, all via `build/libs/` from `collectJars`.
- **Check:** the 1.21.4-fabric jar's entry list matches the Phase 1 jar (only the expected moves), `openapi.json` is unchanged, and `build` + `live` CI are green.

Commit: `build: adopt stonecutter`.

### 3. NeoForge 1.21.4 node
- `settings.gradle.kts`: add `version("1.21.4-neoforge", "1.21.4").buildscript = "build.neoforge.gradle.kts"`.
- `build.neoforge.gradle.kts`: MDG `net.neoforged.moddev` 2.0.148, `neoForge { version = 21.4.161 }`, Java 21.
  - Libraries: the same graph walk as Fabric, fed into MDG's `jarJar`, which is not transitive. Exclude `org.slf4j`, `org.ow2.asm` and jetbrains annotations from both `implementation` and `jarJar`; NeoForge pins slf4j strictly (spike 4).
  - Merge core and server classes into the jar.
  - YACL NeoForge build on compileOnly and dev runtime.
  - `baritone-api-neoforge-1.13.1.jar` is compile-only.
- `src/neoforge/resources/META-INF/neoforge.mods.toml`:
  - `[[mixins]] config="mcdt.mixins.json"`;
  - optional dependencies on `baritone` and `yet_another_config_lib_v3`;
  - client-side only.
  - Services: `NeoForgeMappingProvider` (always Mojang names), `NeoForgeModResourceSource` (finds files through `ModList`).
- `platform/neoforge/` (wrapped in `//? if neoforge {`): `NeoForgeMod` (`@Mod(value = "mcdt", dist = Dist.CLIENT)`) wires NeoForge events to `McdtClient`:

  | Fabric event | NeoForge event |
  |---|---|
  | `CLIENT_STARTED` | `FMLClientSetupEvent` → `enqueueWork(...)` |
  | `END_CLIENT_TICK` | `ClientTickEvent.Post` |
  | `CLIENT_STOPPING` | `GameShuttingDownEvent` |
  | `JOIN` / `DISCONNECT` | `ClientPlayerNetworkEvent.LoggingIn` / `LoggingOut` |
  | `CHAT` / `GAME` messages | `ClientChatReceivedEvent` / its `.System` subclass |
  | chunk load/unload | `ChunkEvent.Load` / `Unload`, client level only |
  | entity load/unload | `EntityJoinLevelEvent` / `EntityLeaveLevelEvent`, client level only |

  Check each event against the NeoForge 21.4 API while implementing.
  - Config screen: `IConfigScreenFactory`, registered only when YACL is loaded.
- Mixins: the same `mcdt.mixins.json`. There is no refmap on NeoForge (runtime names are Mojang names); make sure Loom's refmap key doesn't break NeoForge.
- **Check:** `:1.21.4-neoforge:build`. Live CI node `neoforge::21.4.161` (no Fabric API needed), with and without Baritone.

Commit: `feat(neoforge): add 1.21.4 platform`.

### 4. 1.21.1 nodes (Fabric + NeoForge)
- Add `1.21.1-fabric` and `1.21.1-neoforge` with `versions/1.21.1-*/gradle.properties`:
  - Fabric API for 1.21.1, pinned with SHA-512 for CI;
  - NeoForge 21.1.265;
  - Baritone 1.11.3, pinned SHA-256 per loader jar;
  - YACL, Mod Menu and Parchment for 1.21.1.
- Compile `1.21.1-fabric` and fix errors:
  - Use `//? if >=1.21.2 {` conditionals for small differences.
  - Move repeated breaks (toasts, `GuiGraphics`/render changes from 1.21.2, texture/atlas accessors) into small compat helpers in `game/compat/`.
  - Mixins or accessors that only exist on one side get a mixin config plugin (`IMixinConfigPlugin`) that enables them per version.
  - If a feature can't reasonably be ported to 1.21.1, `capabilities()` turns it off for that node (step 1 makes that clean), and the gap is recorded in `docs/MULTIVERSION.md`.
- `BaritoneBridge`: add conditionals if Baritone 1.11.3's API differs.
- Then `1.21.1-neoforge`, which should only need build wiring.

Commits: one per node, e.g. `feat: support 1.21.1 fabric`, `feat: support 1.21.1 neoforge`.

### 5. CI and release
- `live.yml` matrix: four nodes × `baritone: [installed, absent]`. Each row gives:
  - the portablemc target (`fabric:<mc>:0.19.5` or `neoforge::<ver>`), Java 21, and the dedicated-server version for the flat world;
  - the mods to install (Fabric API URL + SHA-512 only for Fabric, and the node's Baritone jar);
  - the expected capabilities.
- `scripts/ci/live-client.sh` already takes these as env/args.
- `build.yml`: build all nodes (Stonecutter's aggregate `build`) and upload `build/libs/*`.
- `release.yml`: build all nodes and attach every jar plus `openapi.json`.
- `Dockerfile`: stays Fabric 1.21.4 and uses its node jar.

Commit: `ci: test all phase 2 nodes`.

### 6. Docs
- `AGENTS.md`:
  - Stonecutter workflow: switching the active node, the active node file, conditional comment syntax, where loader-specific code and resources go, adding a node.
  - Updated "Add a curated endpoint" recipe (declare or check a capability).
- `README.md`: a compatibility matrix (1.21.1 / 1.21.4 × Fabric / NeoForge).
- `docs/MULTIVERSION.md`: Phase 2 progress and any capability gaps.

Commit: `docs: phase 2 guide and matrix`.

## Critical files
- New: `stonecutter.gradle.kts`, `build.fabric.gradle.kts` (from `build.gradle.kts`), `build.neoforge.gradle.kts`, `versions/<node>/gradle.properties`, `src/fabric/resources/`, `src/neoforge/resources/`.
- New code: `src/main/java/dev/advik/mcdt/platform/neoforge/*`, `client/McdtClient.java`, `core/.../api/v1/Capability.java`, `tests/live/test_capabilities.py`.
- Modified: `settings.gradle.kts`, `client/MinecraftDevToolsClient.java` (becomes `platform/fabric/FabricEntrypoint`), `client/config/McdtConfigScreen.java`, `game/MinecraftGameClient.java`, `core/.../game/GameClient.java`, `core/.../http/Problems.java`, `core/.../api/v1/HealthResponse.java`, `core/.../mcp/McpToolRegistry.java`, `server/.../http/HttpServer.java`, `core/src/testFixtures/.../MockGameClient.java`, `web/src/lib/types.ts`, the WebUI tab definitions, and all three workflows plus `Dockerfile`.
- Reused:
  - the bundled-libs component-graph walk and the jar merge in today's `build.gradle.kts`;
  - `DownloadVerified` (`buildSrc`);
  - the SPI pattern (`ServiceLoader` lookup as in `reflection/GameTypeAdapters.java`);
  - `scripts/ci/live-client.sh`;
  - `tests/live/conftest.py`'s `Api` / `expect` / `record_events`.

## Verification
1. Local: `./gradlew build` builds every node, runs the core, server and per-node tests, and builds the WebUI. Then `cd web && bun run check`.
2. Jar check after step 2: the 1.21.4-fabric jar entries match the Phase 1 jar (only the expected moves), and `openapi.json` is unchanged apart from the new `capabilities` field in health.
3. Unit: `CapabilitiesTest`, plus all existing 310 tests green.
4. Live (GitHub Actions, never local Docker): each of the 4 nodes × Baritone installed/absent runs:
   - the title-screen suite;
   - world load and world checks;
   - Baritone checks (goto walks to the target, settings round trip, processes, MCP tools), or 501 `baritone-not-installed` when absent;
   - capability assertions;
   - the quit flow.
5. On every live run, check that the Baritone world tests actually ran rather than being skipped (junit `world.xml`).
