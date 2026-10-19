<script lang="ts">
  import { onMount, onDestroy } from 'svelte'
  import { appState } from '../stores.svelte'
  import { getHealth, getClient, getPlayer, getWorld, getPlayerSkinInfo, loadPlayerSkinImage } from '../api'
  import { toastStore } from '../toast.svelte'
  import type { HealthResponse, ClientResponse, PlayerResponse, WorldResponse, PlayerSkinResponse } from '../types'
  import {
    Heart,
    Apple,
    Compass,
    Clock,
    Cpu,
    Copy,
    Check,
    Key,
    Activity,
    Layers,
    Boxes,
    Eye,
    User,
    Download
  } from 'lucide-svelte'

  let healthData = $state<HealthResponse | null>(null)
  let clientData = $state<ClientResponse | null>(null)
  let playerData = $state<PlayerResponse | null>(null)
  let worldData = $state<WorldResponse | null>(null)
  let skinData = $state<PlayerSkinResponse | null>(null)
  let skinImageUrl = $state<string | null>(null)
  let skinImageRequested = false
  let pollInterval: number | null = null
  let copiedPos = $state(false)

  async function refreshData() {
    if (typeof window !== 'undefined' && (window as unknown as { __MOCK_DATA__?: { health: HealthResponse; client: ClientResponse; player: PlayerResponse; world: WorldResponse } }).__MOCK_DATA__) {
      const mock = (window as unknown as { __MOCK_DATA__: { health: HealthResponse; client: ClientResponse; player: PlayerResponse; world: WorldResponse } }).__MOCK_DATA__
      healthData = mock.health
      clientData = mock.client
      playerData = mock.player
      worldData = mock.world
      appState.inGame = true
      appState.serverOnline = true
      return
    }

    if (!appState.token) {
      return
    }

    try {
      const [h, c, p, w, s] = await Promise.all([
        getHealth().catch(() => null),
        getClient().catch(() => null),
        getPlayer().catch(() => null),
        getWorld().catch(() => null),
        getPlayerSkinInfo().catch(() => null)
      ])

      healthData = h
      clientData = c
      playerData = p
      worldData = w
      skinData = s

      // The skin rarely changes, so fetch the image once instead of on every poll
      if (p && !skinImageRequested) {
        skinImageRequested = true
        skinImageUrl = await loadPlayerSkinImage().catch(() => null)
      }
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
    if (skinImageUrl) URL.revokeObjectURL(skinImageUrl)
  })

  const playerName = $derived(skinData?.playerName || appState.playerName || 'Player')

  function formatUptime(ms: number): string {
    const totalSeconds = Math.floor(ms / 1000)
    const h = Math.floor(totalSeconds / 3600)
    const m = Math.floor((totalSeconds % 3600) / 60)
    const sec = totalSeconds % 60
    return h > 0 ? `${h}h ${m}m` : `${m}m ${sec}s`
  }

  function copyCoordinates() {
    if (!playerData?.pos) return
    const [x, y, z] = playerData.pos
    const text = `/tp ${x.toFixed(2)} ${y.toFixed(2)} ${z.toFixed(2)}`
    try {
      navigator.clipboard?.writeText(text).catch(() => {})
    } catch {
      // ignore clipboard error in headless environments
    }
    copiedPos = true
    toastStore.show('Teleport command copied to clipboard!', 'success', 10000)
    setTimeout(() => copiedPos = false, 10000)
  }
</script>

<div class="space-y-6">
  {#if !appState.token}
    <!-- Auth Required Alert -->
    <div role="alert" class="alert alert-soft alert-warning border border-warning/30 shadow-md p-6 rounded-box flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
      <div class="flex items-center gap-3">
        <div class="p-3 bg-warning/20 rounded-xl text-warning">
          <Key class="w-6 h-6" />
        </div>
        <div>
          <h3 class="font-bold text-base text-base-content">Authentication Required</h3>
          <p class="text-sm text-base-content/70">Configure your session Bearer token to read live telemetry from Minecraft.</p>
        </div>
      </div>
      <button class="btn btn-warning btn-sm gap-1.5 shrink-0" onclick={() => appState.tokenModalOpen = true}>
        <Key class="w-3.5 h-3.5" />
        <span>Set Token</span>
      </button>
    </div>
  {:else if !appState.inGame}
    <!-- Client Active / Main Menu Card -->
    <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box overflow-hidden">
      <div class="card-body p-6 space-y-4">
        <div class="flex items-center gap-3">
          <div class="p-3 bg-info/10 rounded-xl text-info">
            <Compass class="w-6 h-6" />
          </div>
          <div>
            <h2 class="card-title text-base text-base-content">Minecraft Client Active: Main Menu</h2>
            <p class="text-sm text-base-content/70">
              The Minecraft DevTools HTTP server is active and responding. Player and world telemetry will automatically stream here once a singleplayer world or server is loaded.
            </p>
          </div>
        </div>

        <div class="divider my-1"></div>

        <div class="flex flex-wrap gap-2 text-xs">
          <span class="badge badge-neutral gap-1.5">
            DevTools: {healthData?.mcdtVersion || 'running'}
          </span>
          <span class="badge badge-outline gap-1.5">
            Game: {healthData?.gameVersion || '1.21.4'}
          </span>
          <span class="badge badge-ghost gap-1.5">
            Baritone: {healthData?.baritone || 'not-installed'}
          </span>
        </div>
      </div>
    </div>
  {/if}

  {#if appState.inGame && playerData}
    <!-- 3-Column Modular Dashboard -->
    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
      
      <!-- Card 1: Player Status -->
      <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box">
        <div class="card-body p-5 space-y-4">
          <div class="flex items-center justify-between pb-1 border-b border-base-300">
            <div class="flex items-center gap-2">
              <div class="w-8 h-8 rounded-lg bg-error/10 text-error flex items-center justify-center">
                <Heart class="w-4 h-4 fill-error/20" />
              </div>
              <h2 class="card-title text-sm font-bold tracking-tight">Player: {playerName}</h2>
            </div>
            <span class="badge badge-soft badge-success badge-sm font-medium">In World</span>
          </div>

          <!-- Health Meter -->
          <div class="space-y-1.5">
            <div class="flex justify-between text-xs font-medium">
              <span class="flex items-center gap-1.5 text-base-content/70">
                <Heart class="w-3.5 h-3.5 text-error" /> Health
              </span>
              <span class="font-mono">{(playerData.health ?? 20).toFixed(1)} / {playerData.maxHealth ?? 20}</span>
            </div>
            <progress
              class="progress progress-error w-full h-2.5"
              value={playerData.health ?? 20}
              max={playerData.maxHealth ?? 20}
            ></progress>
          </div>

          <!-- Hunger / Food Meter -->
          <div class="space-y-1.5">
            <div class="flex justify-between text-xs font-medium">
              <span class="flex items-center gap-1.5 text-base-content/70">
                <Apple class="w-3.5 h-3.5 text-warning" /> Hunger / Food
              </span>
              <span class="font-mono">{playerData.food ?? 20} / 20</span>
            </div>
            <progress
              class="progress progress-warning w-full h-2.5"
              value={playerData.food ?? 20}
              max="20"
            ></progress>
          </div>

          <!-- Coordinates Display -->
          {#if playerData.pos}
            <div class="pt-2 border-t border-base-300 space-y-2">
              <div class="text-xs text-base-content/70 font-medium">World Coordinates</div>
              <div class="flex items-center justify-between gap-2 p-2 bg-base-300/60 rounded-lg">
                <kbd class="kbd kbd-sm font-mono text-xs bg-base-100">
                  X: {playerData.pos[0].toFixed(1)} Y: {playerData.pos[1].toFixed(1)} Z: {playerData.pos[2].toFixed(1)}
                </kbd>
                <button
                  class="btn btn-xs {copiedPos ? 'btn-success' : 'btn-ghost'} gap-1"
                  onclick={copyCoordinates}
                  title="Copy teleport command"
                >
                  {#if copiedPos}
                    <Check class="w-3 h-3" />
                    <span>Copied</span>
                  {:else}
                    <Copy class="w-3 h-3" />
                    <span>/tp</span>
                  {/if}
                </button>
              </div>
            </div>
          {/if}
        </div>
      </div>

      <!-- Card 2: World Environment -->
      <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box">
        <div class="card-body p-5 space-y-4">
          <div class="flex items-center justify-between pb-1 border-b border-base-300">
            <div class="flex items-center gap-2">
              <div class="w-8 h-8 rounded-lg bg-primary/10 text-primary flex items-center justify-center">
                <Compass class="w-4 h-4" />
              </div>
              <h2 class="card-title text-sm font-bold tracking-tight">World Environment</h2>
            </div>
            <span class="badge badge-soft badge-primary badge-sm font-medium capitalize">
              {worldData?.dimension?.replace('minecraft:', '') || 'Overworld'}
            </span>
          </div>

          <!-- Key World Attributes -->
          <div class="grid grid-cols-2 gap-2 text-xs">
            <div class="p-2.5 bg-base-300/40 rounded-lg space-y-1">
              <span class="text-base-content/60 block">Difficulty</span>
              <span class="font-semibold text-base-content capitalize">{worldData?.difficulty || 'Normal'}</span>
            </div>
            <div class="p-2.5 bg-base-300/40 rounded-lg space-y-1">
              <span class="text-base-content/60 block">Weather</span>
              <span class="font-semibold text-base-content">
                {worldData?.weather?.raining ? (worldData.weather.thundering ? 'Thunderstorm' : 'Rain') : 'Clear'}
              </span>
            </div>
            <div class="p-2.5 bg-base-300/40 rounded-lg space-y-1">
              <span class="text-base-content/60 block flex items-center gap-1">
                <Boxes class="w-3 h-3" /> Entities
              </span>
              <span class="font-mono font-bold text-base-content">{worldData?.entityCount ?? 0}</span>
            </div>
            <div class="p-2.5 bg-base-300/40 rounded-lg space-y-1">
              <span class="text-base-content/60 block flex items-center gap-1">
                <Layers class="w-3 h-3" /> Chunks
              </span>
              <span class="font-mono font-bold text-base-content">{worldData?.loadedChunks ?? 0}</span>
            </div>
          </div>

          <!-- Day / Night Cycle -->
          {#if worldData?.time}
            <div class="pt-1 space-y-1.5">
              <div class="flex justify-between text-xs font-medium">
                <span class="flex items-center gap-1 text-base-content/70">
                  <Clock class="w-3.5 h-3.5 text-info" /> Day / Night Cycle
                </span>
                <span class="font-mono">{worldData.time.timeOfDay} / 24000 ticks</span>
              </div>
              <progress
                class="progress progress-info w-full h-2"
                value={worldData.time.timeOfDay}
                max="24000"
              ></progress>
            </div>
          {/if}
        </div>
      </div>

      <!-- Card 3: Client Diagnostics -->
      <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box">
        <div class="card-body p-5 space-y-4">
          <div class="flex items-center justify-between pb-1 border-b border-base-300">
            <div class="flex items-center gap-2">
              <div class="w-8 h-8 rounded-lg bg-secondary/10 text-secondary flex items-center justify-center">
                <Cpu class="w-4 h-4" />
              </div>
              <h2 class="card-title text-sm font-bold tracking-tight">Client Diagnostics</h2>
            </div>
            <span class="badge badge-neutral badge-sm font-mono">{clientData?.fps ?? appState.fps} FPS</span>
          </div>

          <div class="space-y-2.5 text-xs">
            <div class="flex items-center justify-between p-2 rounded-lg bg-base-300/40">
              <span class="text-base-content/70">Client Tick</span>
              <span class="font-mono font-bold">{healthData?.tick ?? appState.tick}</span>
            </div>

            <div class="flex items-center justify-between p-2 rounded-lg bg-base-300/40">
              <span class="text-base-content/70">Uptime</span>
              <span class="font-mono font-semibold">{healthData ? formatUptime(healthData.uptimeMs) : '-'}</span>
            </div>

            <div class="flex items-center justify-between p-2 rounded-lg bg-base-300/40">
              <span class="text-base-content/70">Task Queue</span>
              <span class="font-mono font-semibold">
                {healthData ? `${healthData.queue.depth} / ${healthData.queue.capacity} · p99 ${healthData.queue.p99TaskMicros} µs` : '-'}
              </span>
            </div>

            <div class="flex items-center justify-between p-2 rounded-lg bg-base-300/40">
              <span class="text-base-content/70">Baritone AI Pathfinding</span>
              <span class="badge badge-sm {healthData?.baritone && healthData.baritone !== 'not-installed' ? 'badge-success' : 'badge-ghost'}">
                {healthData?.baritone ?? 'not-installed'}
              </span>
            </div>

            <div class="flex items-center justify-between p-2 rounded-lg bg-base-300/40">
              <span class="text-base-content/70">API Permission</span>
              <span class="badge badge-sm badge-success font-mono">{healthData?.permission ?? 'read'}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Card 4: Player Skin & Appearance -->
      <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box md:col-span-2 lg:col-span-3">
        <div class="card-body p-5">
          <div class="flex items-center justify-between pb-3 border-b border-base-300 mb-4">
            <div class="flex items-center gap-2">
              <div class="w-8 h-8 rounded-lg bg-info/10 text-info flex items-center justify-center">
                <User class="w-4 h-4" />
              </div>
              <h2 class="card-title text-sm font-bold tracking-tight">Player Skin & Model</h2>
            </div>
            {#if skinData}
              <span class="badge badge-soft badge-info badge-sm capitalize font-medium">
                {skinData.model} Arms
              </span>
            {/if}
          </div>

          <div class="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-6">
            <div class="flex items-center gap-4">
              <div class="relative w-16 h-16 bg-base-300/80 rounded-xl overflow-hidden flex items-center justify-center border border-base-300 shrink-0 shadow-inner">
                {#if skinImageUrl}
                  <img
                    src={skinImageUrl}
                    alt="Player Skin Texture"
                    class="w-full h-full object-contain [image-rendering:pixelated]"
                  />
                {:else}
                  <User class="w-6 h-6 text-base-content/30" />
                {/if}
              </div>
              <div class="space-y-1 text-xs">
                <div class="font-semibold text-base-content text-sm">{playerName}</div>
                <div class="text-base-content/70">Model: <span class="badge badge-xs badge-neutral capitalize font-mono">{skinData?.model || 'wide'}</span></div>
                <div class="text-base-content/70">Security: <span class="badge badge-xs {skinData?.secure ? 'badge-success' : 'badge-ghost'}">{skinData?.secure ? 'Official Mojang Profile' : 'Default / Custom'}</span></div>
              </div>
            </div>

            <div class="flex flex-wrap items-center gap-2">
              {#if skinData?.textureUrl}
                <a
                  href={skinData.textureUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  class="btn btn-xs btn-outline btn-ghost gap-1"
                >
                  <span>Mojang URL</span>
                </a>
              {/if}
              {#if skinImageUrl}
                <a
                  href={skinImageUrl}
                  download="{playerName}_skin.png"
                  class="btn btn-xs btn-outline btn-primary gap-1"
                >
                  <Download class="w-3.5 h-3.5" />
                  <span>Download Skin PNG</span>
                </a>
              {/if}
            </div>
          </div>
        </div>
      </div>

    </div>
  {/if}
</div>
