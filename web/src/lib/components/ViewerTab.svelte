<script lang="ts">
  import { onMount, onDestroy } from 'svelte'
  import { WorldViewer, type ViewerStats } from '../viewer/WorldViewer'
  import {
    Box, Maximize2, Minimize2, Sliders, Zap, Cpu, Activity, Layers, Compass, Eye, Lock, Unlock, RotateCcw, Sun, Users
  } from 'lucide-svelte'

  let canvas = $state<HTMLCanvasElement | null>(null)
  let container = $state<HTMLDivElement | null>(null)
  let labelHost = $state<HTMLDivElement | null>(null)

  let viewer: WorldViewer | null = null
  let resizeObserver: ResizeObserver | null = null
  let statsTimer: number | null = null

  let loading = $state(true)
  let error = $state<string | null>(null)
  let isLocked = $state(false)
  let isFullscreen = $state(false)
  let debugOpen = $state(false)

  let radius = $state(8)
  let follow = $state(false)
  let showEntities = $state(true)
  let wireframe = $state(false)
  let fullbright = $state(false)
  let timeMode = $state<'game' | 'noon' | 'night'>('game')

  let stats = $state<ViewerStats | null>(null)

  onMount(async () => {
    if (!canvas || !labelHost) return
    viewer = new WorldViewer(canvas, { radius, follow, entities: showEntities })
    viewer.onLockChange = locked => (isLocked = locked)
    try {
      await viewer.start(labelHost)
    } catch (err) {
      error = err instanceof Error ? err.message : String(err)
    }
    loading = false

    resizeObserver = new ResizeObserver(() => {
      if (container) viewer?.resize(container.clientWidth, container.clientHeight)
    })
    if (container) resizeObserver.observe(container)
    statsTimer = window.setInterval(() => {
      if (viewer) stats = viewer.getStats()
    }, 250)
    document.addEventListener('fullscreenchange', onFullscreenChange)
  })

  onDestroy(() => {
    if (statsTimer !== null) clearInterval(statsTimer)
    resizeObserver?.disconnect()
    document.removeEventListener('fullscreenchange', onFullscreenChange)
    viewer?.dispose()
    viewer = null
  })

  function onFullscreenChange() {
    isFullscreen = document.fullscreenElement === container
  }

  function toggleFullscreen() {
    if (!container) return
    if (!document.fullscreenElement) container.requestFullscreen?.().catch(() => null)
    else document.exitFullscreen?.().catch(() => null)
  }

  function toggleFollow() {
    follow = !follow
    viewer?.setFollow(follow)
  }

  function toggleEntities() {
    showEntities = !showEntities
    viewer?.setEntities(showEntities)
  }

  function toggleWireframe() {
    wireframe = !wireframe
    viewer?.setWireframe(wireframe)
  }

  function toggleFullbright() {
    fullbright = !fullbright
    viewer?.setFullbright(fullbright)
  }

  function applyTime(mode: string) {
    // Read the event's value: bind:value may not have updated timeMode yet when onchange runs.
    viewer?.setTimeOverride(mode === 'game' ? null : mode === 'noon' ? 1 : 0.2)
  }

  function applyRadius() {
    viewer?.setRadius(radius)
  }

  const backend = $derived(stats?.render.backend ?? 'WebGPU')
  const wsStatus = $derived(stats?.wsStatus ?? 'disconnected')
</script>

<div class="flex flex-col gap-3 h-[calc(100vh-12rem)] min-h-[550px] relative">
  <div class="flex flex-wrap items-center justify-between gap-2 bg-base-200/80 backdrop-blur border border-base-300 rounded-xl px-4 py-2.5">
    <div class="flex items-center gap-3">
      <div class="flex items-center gap-2 font-semibold text-base text-base-content">
        <Box class="w-5 h-5 text-primary" />
        <span>3D World Viewer</span>
      </div>
      {#if backend === 'WebGPU'}
        <span class="badge badge-success badge-sm font-mono gap-1 font-bold"><Zap class="w-3 h-3" /> WebGPU</span>
      {:else}
        <span class="badge badge-warning badge-sm font-mono gap-1 font-bold"><Cpu class="w-3 h-3" /> WebGL 2</span>
      {/if}
      <div class="flex items-center gap-1.5 text-xs text-base-content/70">
        <span class="inline-block w-2 h-2 rounded-full {wsStatus === 'connected' ? 'bg-success' : wsStatus === 'connecting' ? 'bg-warning animate-pulse' : 'bg-error'}"></span>
        <span class="capitalize font-mono">{wsStatus}</span>
        {#if stats?.dimension}<span class="font-mono text-base-content/50">· {stats.dimension}</span>{/if}
      </div>
    </div>

    <div class="flex items-center gap-4 text-xs font-mono text-base-content/80">
      {#if stats}
        <div class="hidden sm:flex items-center gap-1">
          <Compass class="w-3.5 h-3.5 text-primary" />
          <span>{stats.camera.join(', ')}</span>
        </div>
        <div class="flex items-center gap-1">
          <Layers class="w-3.5 h-3.5 text-secondary" />
          <span>{stats.sections} sections</span>
        </div>
        <div class="flex items-center gap-1">
          <Activity class="w-3.5 h-3.5 text-accent" />
          <span>{stats.render.fps} FPS</span>
        </div>
      {/if}
    </div>

    <div class="flex flex-wrap items-center gap-2">
      <label class="flex items-center gap-2 text-xs" title="Streamed chunk radius">
        <span class="text-base-content/60">Radius {radius}</span>
        <input type="range" min="2" max="32" bind:value={radius} onchange={applyRadius} class="range range-xs range-primary w-24" />
      </label>
      <select class="select select-xs" bind:value={timeMode} onchange={e => applyTime(e.currentTarget.value)} title="Sky light">
        <option value="game">Game time</option>
        <option value="noon">Noon</option>
        <option value="night">Night</option>
      </select>
      <button class="btn btn-xs {follow ? 'btn-primary' : 'btn-ghost border-base-300'} gap-1" onclick={toggleFollow} title="Mirror the player's camera">
        <Eye class="w-3.5 h-3.5" />
        <span class="hidden md:inline">{follow ? 'Following' : 'Follow'}</span>
      </button>
      {#if stats?.player && !follow}
        <button class="btn btn-xs btn-ghost border-base-300" onclick={() => viewer?.snapToPlayer()} title="Move the camera to the player">
          <RotateCcw class="w-3.5 h-3.5" />
        </button>
      {/if}
      <button class="btn btn-xs {showEntities ? 'btn-secondary' : 'btn-ghost border-base-300'}" onclick={toggleEntities} title="Show entities">
        <Users class="w-3.5 h-3.5" />
      </button>
      <button class="btn btn-xs {fullbright ? 'btn-accent' : 'btn-ghost border-base-300'}" onclick={toggleFullbright} title="Ignore light levels">
        <Sun class="w-3.5 h-3.5" />
      </button>
      <button class="btn btn-xs {debugOpen ? 'btn-secondary' : 'btn-ghost border-base-300'}" onclick={() => (debugOpen = !debugOpen)} title="Diagnostics">
        <Sliders class="w-3.5 h-3.5" />
      </button>
      <button class="btn btn-xs btn-ghost border-base-300" onclick={toggleFullscreen} title="Fullscreen">
        {#if isFullscreen}<Minimize2 class="w-3.5 h-3.5" />{:else}<Maximize2 class="w-3.5 h-3.5" />{/if}
      </button>
    </div>
  </div>

  <div bind:this={container} class="flex-1 w-full h-full relative rounded-xl overflow-hidden border border-base-300 bg-neutral select-none">
    <canvas bind:this={canvas} class="w-full h-full block cursor-crosshair"></canvas>
    <div bind:this={labelHost} class="absolute inset-0 pointer-events-none"></div>

    {#if loading}
      <div class="absolute inset-0 bg-base-300/80 backdrop-blur-sm flex flex-col items-center justify-center gap-3 z-10 text-base-content">
        <span class="loading loading-spinner loading-lg text-primary"></span>
        <p class="font-medium">Loading block atlas…</p>
      </div>
    {:else if error}
      <div class="absolute inset-0 flex items-center justify-center z-10">
        <div role="alert" class="alert alert-error max-w-md">
          <span>Viewer failed to start: {error}</span>
        </div>
      </div>
    {:else if wsStatus === 'connected' && stats && !stats.dimension}
      <div class="absolute inset-0 flex items-center justify-center z-10 pointer-events-none">
        <div class="alert max-w-md"><span>No world loaded. Join a world to see it here.</span></div>
      </div>
    {/if}

    {#if !isLocked && !follow && !loading && !error}
      <div class="absolute bottom-4 left-1/2 -translate-x-1/2 bg-base-100/90 backdrop-blur border border-base-300 text-base-content px-4 py-2 rounded-full shadow-lg text-xs flex items-center gap-3 pointer-events-none">
        <div class="flex items-center gap-1.5 font-medium text-primary">
          <Lock class="w-3.5 h-3.5" />
          <span>Click to fly</span>
        </div>
        <div class="flex items-center gap-1 text-base-content/70">
          <kbd class="kbd kbd-xs">W</kbd><kbd class="kbd kbd-xs">A</kbd><kbd class="kbd kbd-xs">S</kbd><kbd class="kbd kbd-xs">D</kbd> Move
          <span>&bull;</span><kbd class="kbd kbd-xs">Space</kbd>/<kbd class="kbd kbd-xs">Shift</kbd> Up/Down
          <span>&bull;</span><kbd class="kbd kbd-xs">Ctrl</kbd> Sprint
          <span>&bull;</span>Wheel speed
        </div>
      </div>
    {/if}

    {#if isLocked}
      <div class="absolute top-4 right-4 bg-base-100/80 backdrop-blur border border-base-300 text-xs px-2.5 py-1 rounded-md text-base-content/80 flex items-center gap-1.5 pointer-events-none">
        <Unlock class="w-3 h-3 text-success" />
        <span><kbd class="kbd kbd-xs">Esc</kbd> to release</span>
      </div>
    {/if}

    {#if debugOpen && stats}
      <div class="absolute top-3 left-3 w-80 max-h-[calc(100%-1.5rem)] overflow-y-auto bg-base-200/95 backdrop-blur-md border border-base-300 rounded-xl p-4 shadow-2xl z-20 text-xs flex flex-col gap-3">
        <div class="flex items-center justify-between border-b border-base-300 pb-2">
          <div class="flex items-center gap-2 font-bold text-sm text-base-content">
            <Sliders class="w-4 h-4 text-primary" />
            <span>Diagnostics</span>
          </div>
          <button class="btn btn-ghost btn-circle btn-xs" onclick={() => (debugOpen = false)}>✕</button>
        </div>

        {#snippet row(label: string, value: string | number)}
          <div class="flex justify-between gap-2">
            <span class="text-base-content/60">{label}</span>
            <span class="text-right truncate" title={String(value)}>{value}</span>
          </div>
        {/snippet}

        <div class="flex flex-col gap-1.5">
          <span class="font-semibold text-base-content/60 uppercase tracking-wider text-[10px]">Graphics</span>
          <div class="bg-base-300/50 p-2.5 rounded-lg flex flex-col gap-1 font-mono text-[11px]">
            {@render row('Backend', stats.render.backend)}
            {@render row('Adapter', stats.render.adapterName)}
            {@render row('Vendor', stats.render.adapterVendor)}
            {@render row('Frame', `${stats.render.fps} FPS · ${stats.render.frameTimeMs} ms`)}
            {@render row('Draw calls', stats.render.drawCalls)}
            {@render row('Triangles drawn', stats.render.triangles.toLocaleString())}
            {@render row('Geometries', stats.render.geometries)}
          </div>
        </div>

        <div class="flex flex-col gap-1.5">
          <span class="font-semibold text-base-content/60 uppercase tracking-wider text-[10px]">World</span>
          <div class="bg-base-300/50 p-2.5 rounded-lg flex flex-col gap-1 font-mono text-[11px]">
            {@render row('Chunks received', stats.chunksReceived)}
            {@render row('Data received', `${stats.megabytesReceived} MB`)}
            {@render row('Sections meshed', stats.sections)}
            {@render row('Upload queue', stats.meshQueue)}
            {@render row('Triangles loaded', stats.triangles.toLocaleString())}
            {@render row('Block models', stats.modelsKnown)}
            {@render row('Entities', stats.entities)}
            {#if stats.player}{@render row('Player', stats.player.join(', '))}{/if}
          </div>
        </div>

        <div class="flex gap-2">
          <button class="btn btn-xs flex-1 {wireframe ? 'btn-accent' : 'btn-ghost border-base-300'}" onclick={toggleWireframe}>Wireframe</button>
          <button class="btn btn-xs flex-1 btn-ghost border-base-300" onclick={() => viewer?.remesh()}>Remesh all</button>
        </div>
      </div>
    {/if}
  </div>
</div>
