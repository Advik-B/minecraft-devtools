# Contributing to MCDT

Thanks for taking a look. This page covers how to build MCDT, run the tests, and get a change merged.

A few other docs are worth knowing about:

- [AGENTS.md](AGENTS.md) explains the architecture and the rules the code has to follow (threading, errors, permissions, reflection safety). It's written for coding agents, but it's the best map of the code for people too. Read the "Core invariants" part before you change anything in `core/`, `server/` or `src/`.
- [MCDT.md](MCDT.md) is the design doc and roadmap. Parts of it describe things that aren't built yet, so check `server/src/main/java/dev/advik/mcdt/http/HttpServer.java` to see which routes really exist.
- [docs/MULTIVERSION.md](docs/MULTIVERSION.md) is the plan for supporting more Minecraft versions and loaders.

## What you need

- A JDK, version 21 or newer. CI builds with JDK 25.
- [Bun](https://bun.sh) on your `PATH`. The Gradle build compiles the dashboard and packs it into the mod jar, so it fails without Bun.
- [uv](https://docs.astral.sh/uv/) if you want to run the live test suite.
- Network access the first time you build. Gradle downloads the pinned Baritone and Scalar files and checks their SHA-256.

## Build and test

```bash
./gradlew build                      # every node's mod jar in build/libs/, dashboard and libraries bundled
./gradlew :1.21.1-neoforge:build     # just one node
./gradlew test                       # JUnit 5 suite (no Minecraft needed) + JaCoCo report
./gradlew :server:test --tests 'dev.advik.mcdt.mcp.McpToolsTest'   # one test class
./gradlew :1.21.4-fabric:runClient   # dev client in ./run (with Mod Menu + YACL on Fabric)
```

On Windows, use `gradlew.bat`.

## Test against a real game

The unit tests run against a mock game client. They catch broken routes and bad JSON, but they can't tell you whether something works in Minecraft. Before you call a change done, try it on a running client.

1. Start a dev client: `./gradlew :1.21.4-fabric:runClient` (or another node).
2. On the title screen, run the live suite:

   ```bash
   uv run --project tests/live pytest tests/live
   ```

   Inside a world, add `--state world`. `--baritone installed` or `--baritone absent` checks the Baritone mode you expect, and `--shutdown` ends with the quit flow.

CI runs the same suite on headless clients for every node, once with Baritone and once without.

## Work on the dashboard

The dashboard lives in `web/` (Svelte 5, Vite, Tailwind 4, daisyUI 5). For hot reload:

```bash
cd web
bun install
bun run dev        # Vite on :5173, proxies /v1, /mcp and /docs to :25580
bun run check      # type-check
```

Add `http://localhost:5173` to `originAllowlist` in `run/config/mcdt/config.json`, or the API rejects the dev server's requests.

If you'd rather skip Vite, `runClient` serves the dashboard straight from `web/dist`. Run `bun run build` and reload the browser. No game restart needed.

## Minecraft versions and loaders

The mod is built with [Stonecutter](https://stonecutter.kikugie.dev). Each Minecraft version and loader pair is a node (`1.21.4-fabric`, `1.21.1-neoforge`, ...), and they all build from the same `src/`.

`src/` always holds the code for the active node, which is `1.21.4-fabric` in git. To work on another one:

```bash
./gradlew "Set active project to 1.21.1-neoforge"
```

Switch back with `./gradlew "Reset active project"` before you commit. Otherwise your diff will be full of rewritten comments.

Version and loader differences use comments like `//? if fabric {` and `//? if >=1.21.2 {`. AGENTS.md has the details, including how to add a new node.

## Build the Docker image

```bash
docker build --build-arg NODE=1.21.1-neoforge -t mcdt .
```

`docker compose up -d --build` builds from your checkout too. The Baritone jar in the image is the one in `libs/`, pinned by SHA-256 in `versions/<node>/gradle.properties`.

CI (`.github/workflows/docker.yml`) builds every image, runs the live suite against it, and publishes it. Rely on that rather than local Docker runs to check image changes.

## Project layout

```
core/            no Minecraft types: GameClient seam, DTOs, task queue, events, reflection, MCP tools, capabilities
server/          Javalin server, route handlers (@OpenApi), WebSockets, security, MCP transport
src/main/java/dev/advik/mcdt/
  game/          the real Minecraft implementation of GameClient
  client/        loader-neutral lifecycle (start, tick, events) and the config screen
  platform/      fabric/ and neoforge/ entrypoints and loader SPIs
  mixin/         mixins and accessors
  baritone/      optional integration (only bridge/ touches Baritone)
src/<loader>/    loader metadata (fabric.mod.json, neoforge.mods.toml)
versions/<node>/ per-node versions (gradle.properties)
web/             Svelte 5 + Tailwind 4 + daisyUI dashboard
tests/live/      Python live suite run against a real client
scripts/         MCP stdio shims and the CI live-client runner
docker/          container entrypoint and mod installer
http/            sample requests per area
```

## Sending a pull request

- Branch off `master` and open a PR against it.
- Use [Conventional Commits](https://www.conventionalcommits.org): `feat:`, `fix(build):`, `docs:`, `feat(web):` and so on.
- Java and Gradle files use tabs. Code in `web/` uses 2 spaces and no semicolons. Line endings are LF everywhere except `*.bat`.
- Keep comments short and only where the code isn't obvious.
- Don't reformat code you aren't changing, and don't bump pinned versions or checksums unless that's the point of the PR.
- Before you open it, run `./gradlew test`, run `bun run check` if you touched `web/`, and try the change on a real client.

Adding an endpoint, a config setting or an event? AGENTS.md has a step-by-step recipe for each.
