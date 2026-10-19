<script lang="ts">
  import { onMount, onDestroy } from 'svelte'
  import { appState } from '../stores.svelte'
  import { getHealth, getClient, getPlayer, getWorld } from '../api'
  import type { HealthResponse, ClientResponse, PlayerResponse, WorldResponse } from '../types'

  let healthData = $state<HealthResponse | null>(null)
  let clientData = $state<ClientResponse | null>(null)
  let playerData = $state<PlayerResponse | null>(null)
  let worldData = $state<WorldResponse | null>(null)
  let pollInterval: number | null = null
  let copiedPos = $state(false)

  async function refreshData() {
    if (!appState.token) {
      return
    }

    try {
      const [h, c, p, w] = await Promise.all([
        getHealth().catch(() => null),
        getClient().catch(() => null),
        getPlayer().catch(() => null),
        getWorld().catch(() => null)
      ])

      healthData = h
      clientData = c
      playerData = p
      worldData = w
    } catch {
      // ignore
    }
  }

  onMount(() => {
    refreshData()
    pollInterval = window.setInterval(refreshData, 1200)
  })

  onDestroy(() => {
    if (pollInterval !== null) {
      clearInterval(pollInterval)
      pollInterval = null
    }
  })

  function copyCoordinates() {
    if (!playerData?.position) return
    const { x, y, z } = playerData.position
    const text = `/tp ${x.toFixed(2)} ${y.toFixed(2)} ${z.toFixed(2)}`
    navigator.clipboard.writeText(text)
    copiedPos = true
    setTimeout(() => copiedPos = false, 1500)
  }
</script>

{#if !appState.token}
  <article>
    <header><strong>Authentication Required</strong></header>
    <p>Configure your session Bearer token to read live telemetry from Minecraft.</p>
    <footer>
      <button onclick={() => appState.tokenModalOpen = true}>Set Token</button>
    </footer>
  </article>
{:else if !appState.inGame}
  <article>
    <header><strong>Minecraft Client Active: Main Menu</strong></header>
    <p>
      The MCDT HTTP server is active and responding. Player and world telemetry will automatically stream here once a singleplayer world or server is loaded.
    </p>
    <footer>
      <small>
        MCDT: {healthData?.mcdtVersion || 'running'} &bull; 
        Game: {healthData?.gameVersion || '1.21.4'} &bull; 
        Baritone: {healthData?.baritone || 'not-installed'}
      </small>
    </footer>
  </article>
{/if}

{#if appState.inGame && playerData}
  <div class="grid">
    <article>
      <header><strong>Player: {playerData.name}</strong></header>
      <label for="health-bar">Health ({(playerData.health ?? 20).toFixed(1)} / {playerData.maxHealth ?? 20})</label>
      <progress id="health-bar" value={playerData.health ?? 20} max={playerData.maxHealth ?? 20}></progress>

      <label for="food-bar">Food ({playerData.foodLevel ?? 20})</label>
      <progress id="food-bar" value={playerData.foodLevel ?? 20} max="20"></progress>

      {#if playerData.position}
        <p>
          Coordinates: <code>X: {playerData.position.x.toFixed(1)} Y: {playerData.position.y.toFixed(1)} Z: {playerData.position.z.toFixed(1)}</code>
        </p>
        <button class="outline" onclick={copyCoordinates}>
          {copiedPos ? 'Copied!' : 'Copy /tp Command'}
        </button>
      {/if}
    </article>

    <article>
      <header><strong>World Environment</strong></header>
      <p>Dimension: <mark>{worldData?.dimension?.replace('minecraft:', '') || 'Overworld'}</mark></p>
      <p>Difficulty: <strong>{worldData?.difficulty || 'Normal'}</strong></p>
      <p>Weather: <strong>{worldData?.isRaining ? (worldData?.isThundering ? 'Thunderstorm' : 'Rain') : 'Clear'}</strong></p>
      <p>Loaded Entities: <strong>{worldData?.loadedEntitiesCount ?? 0}</strong></p>
      <p>Loaded Chunks: <strong>{worldData?.loadedChunksCount ?? 0}</strong></p>
      {#if worldData?.dayTime !== undefined}
        <label for="day-cycle">Day/Night Cycle (Tick {worldData.dayTime % 24000} / 24000)</label>
        <progress id="day-cycle" value={worldData.dayTime % 24000} max="24000"></progress>
      {/if}
    </article>

    <article>
      <header><strong>Client Diagnostics</strong></header>
      <p>Client FPS: <strong>{clientData?.fps ?? appState.fps}</strong></p>
      <p>Current Tick: <strong>{appState.tick || healthData?.eventRingBufferHead || 'Active'}</strong></p>
      <p>Event Buffer Capacity: <strong>{healthData?.eventRingBufferSize ?? 1024}</strong></p>
      <p>Baritone: <strong>{healthData?.baritone ?? 'not-installed'}</strong></p>
    </article>
  </div>
{/if}
