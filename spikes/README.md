# Phase 0 spikes

Throwaway experiments for the multi-version plan in `docs/MULTIVERSION.md`. Nothing here ships.

- `legacy/`: one MCP-named source tree built with Unimined for Forge 1.8.9, Forge 1.12.2, Legacy Fabric 1.8.9 and Legacy Fabric 1.12.2. Each jar waits for the title screen, self-tests a loopback HTTP server, prints `MCDT-SPIKE RESULT ...`, writes `mcdt-spike.txt` and quits.

Build locally with `./gradlew -p spikes/legacy build`. Launch tests run only in GitHub Actions (`.github/workflows/spikes.yml`).
