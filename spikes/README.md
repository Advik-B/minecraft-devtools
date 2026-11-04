# Phase 0 spikes

Throwaway experiments for the multi-version plan in `docs/MULTIVERSION.md`. Nothing here ships.

- `legacy/`: one MCP-named source tree built with Unimined for Forge 1.8.9, Forge 1.12.2, Legacy Fabric 1.8.9 and Legacy Fabric 1.12.2. Each jar waits for the title screen, self-tests a loopback HTTP server, prints `MCDT-SPIKE RESULT ...`, writes `mcdt-spike.txt` and quits.

Build locally with `./gradlew -p spikes/legacy build`. Launch tests run only in GitHub Actions (`.github/workflows/spikes.yml`).
- `neoforge/`: NeoForge 1.21.1 (ModDevGradle) with the same jar-in-jar libraries as the Fabric build (Javalin 7, Jetty 12, Jackson, OpenAPI plugins, Kotlin stdlib). On the title screen it starts Javalin, calls a JSON route and a WebSocket echo, prints the result and quits.
- `downgrade/`: real MCDT core sources (json, config, exec, events, discovery, Problems, every DTO) and their unit tests, compiled at Java 17, downgraded to Java 8 with JvmDowngrader, and run on a Java 8 JVM (`./gradlew -p spikes/downgrade testOnJava8`).
